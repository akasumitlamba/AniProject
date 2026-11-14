package app.anihome.launcher.nav;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Shizuku user service for gesture navigation: keeps a stable left-to-right order of recent
 * apps (switching never reshuffles it), switches tasks, renders neighbour previews and drives
 * [NativeAnimation]. Transaction codes and behaviour match the tested Edge Navigation 3.3.
 *
 * 1 switch(dir) → String · 2 begin(axis, sign, w, h, callback) → "HOME"/"APP"/"NONE"
 * 3 move(dx, dy) oneway · 4 end(outcome, dir) oneway · 5 preview(sign) oneway · 6 isHome() → int
 */
// Only ever started on Android 10 (see NavigationService.supported()); hidden APIs exist there.
@android.annotation.SuppressLint("NewApi")
public class TaskService extends Binder {
    static final String DESCRIPTOR = "app.anihome.launcher.nav.TaskService";
    private static final String TAG = "AniHomeNavNative";
    private static final String OWN_PACKAGE = "app.anihome.launcher";

    final NativeAnimation animation = new NativeAnimation();
    final ArrayList<Integer> order = new ArrayList<>();
    IBinder callback;
    int cursor = -1;
    int currentTask = -1;
    boolean currentIsHome, gestureActive, gestureFromHome;
    int gestureAxis;

    public TaskService() {
        attachInterface(null, DESCRIPTOR);
    }

    Object activityTasks() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        if (code == 16777115) { // Shizuku asks user services to exit.
            animation.close();
            new Thread(() -> {
                try { Thread.sleep(150); } catch (Exception ignored) { /* exiting anyway */ }
                System.exit(0);
            }).start();
            return true;
        }
        if (code < 1 || code > 6) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        long identity = Binder.clearCallingIdentity();
        try {
            if (code == 6) {
                refreshOrder();
                reply.writeNoException();
                reply.writeInt(currentIsHome ? 1 : 0);
            } else if (code == 1) {
                String result = switchTask(data.readInt());
                reply.writeNoException();
                reply.writeString(result);
            } else if (code == 2) {
                int axis = data.readInt();
                int sign = data.readInt();
                int width = data.readInt();
                int height = data.readInt();
                callback = data.readStrongBinder();
                refreshOrder();
                gestureActive = true;
                gestureAxis = axis;
                gestureFromHome = currentIsHome;
                animation.begin(width, height, currentTask, axis, currentIsHome);
                if (axis == 2) new Thread(() -> preview(sign), "NavPreview").start();
                reply.writeNoException();
                reply.writeString(currentIsHome ? "HOME" : "APP");
            } else if (code == 3) {
                float dx = data.readFloat();
                float dy = data.readFloat();
                int next = cursor < 0 ? 0 : cursor + (dx >= 0f ? 1 : -1);
                // Past the last app the page resists instead of following the finger.
                if (gestureAxis == 2 && (next < 0 || next >= order.size())) dx *= 0.18f;
                animation.move(dx, dy);
            } else if (code == 4) {
                int outcome = data.readInt();
                int direction = data.readInt();
                animation.end(outcome, direction, () -> finished(outcome, direction));
            } else if (code == 5) {
                int sign = data.readInt();
                new Thread(() -> preview(sign), "NavPreview").start();
            }
        } catch (Exception failure) {
            if (reply != null && (flags & FLAG_ONEWAY) == 0) {
                reply.writeNoException();
                if (code == 6) reply.writeInt(0); // Unknown: treat as not Home.
                else reply.writeString("ERROR " + failure);
            }
            Log.e(TAG, "Request", failure);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    /** After the finishing animation: switch if asked, then tell the client what happened. */
    private void finished(int outcome, int direction) {
        if (outcome == 2) {
            try { Log.i(TAG, "Spatial switch " + switchTask(direction)); }
            catch (Exception failure) { Log.e(TAG, "Switch", failure); }
        }
        IBinder target = callback;
        if (target != null) {
            Parcel parcel = Parcel.obtain();
            try {
                // Recents from an app finishes at Home first; the client then opens Recents (4).
                parcel.writeInt(outcome == 1 && !gestureFromHome ? 4 : outcome);
                target.transact(2, parcel, null, FLAG_ONEWAY);
            } catch (Exception ignored) {
                /* Client gone; nothing to report. */
            } finally {
                parcel.recycle();
            }
        }
        gestureActive = false;
    }

    /** Snapshot and label of the neighbouring app in [sign]'s direction, sent to the client. */
    void preview(int sign) {
        try {
            refreshOrder();
            int index = cursor < 0 ? 0 : cursor + sign;
            String label = "End of app list";
            Bitmap bitmap = null;
            if (index >= 0 && index < order.size()) {
                int task = order.get(index);
                label = "Recent app";
                try {
                    Class<?> service = Class.forName("android.app.IActivityTaskManager");
                    Object snapshot = service.getMethod("getTaskSnapshot", Integer.TYPE, Boolean.TYPE).invoke(activityTasks(), task, false);
                    if (snapshot != null) {
                        Object buffer = snapshot.getClass().getMethod("getSnapshot").invoke(snapshot);
                        Object colorSpace = snapshot.getClass().getMethod("getColorSpace").invoke(snapshot);
                        java.lang.reflect.Method wrap = Bitmap.class.getDeclaredMethod("wrapHardwareBuffer",
                            Class.forName("android.graphics.GraphicBuffer"), ColorSpace.class);
                        Bitmap hardware = (Bitmap) wrap.invoke(null, buffer, colorSpace);
                        if (hardware != null) {
                            bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            hardware.recycle();
                        }
                    }
                } catch (Exception failure) {
                    Log.i(TAG, "Task preview unavailable: " + failure);
                }
                Object recent = Class.forName("android.app.IActivityTaskManager")
                    .getMethod("getRecentTasks", Integer.TYPE, Integer.TYPE, Integer.TYPE).invoke(activityTasks(), 40, 0, 0);
                @SuppressWarnings("unchecked")
                List<ActivityManager.RecentTaskInfo> list = (List<ActivityManager.RecentTaskInfo>)
                    Class.forName("android.content.pm.ParceledListSlice").getMethod("getList").invoke(recent);
                for (ActivityManager.RecentTaskInfo info : list) {
                    if (info.taskId != task) continue;
                    if (info.taskDescription != null && info.taskDescription.getLabel() != null) label = info.taskDescription.getLabel();
                    else if (info.baseIntent.getComponent() != null) label = info.baseIntent.getComponent().getPackageName();
                    break;
                }
            }
            IBinder target = callback;
            if (target != null) {
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.writeInt(index >= 0 && index < order.size() ? 1 : 0);
                    parcel.writeString(label);
                    parcel.writeParcelable(bitmap, 0);
                    target.transact(1, parcel, null, FLAG_ONEWAY);
                } finally {
                    parcel.recycle();
                }
            }
            if (bitmap != null) bitmap.recycle();
        } catch (Exception failure) {
            Log.w(TAG, "Preview", failure);
        }
    }

    /**
     * Recent apps in a stable spatial order: known tasks keep their places, new ones are added
     * on the left, closed ones drop out. Home screens and AniHome's own panels are skipped.
     */
    synchronized void refreshOrder() throws Exception {
        Object tasks = activityTasks();
        Class<?> service = Class.forName("android.app.IActivityTaskManager");
        Object recent = service.getMethod("getRecentTasks", Integer.TYPE, Integer.TYPE, Integer.TYPE).invoke(tasks, 40, 0, 0);
        @SuppressWarnings("unchecked")
        List<ActivityManager.RecentTaskInfo> list = (List<ActivityManager.RecentTaskInfo>) recent.getClass().getMethod("getList").invoke(recent);
        ArrayList<Integer> fresh = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo info : list) {
            ComponentName component = info.baseIntent.getComponent();
            if (component == null) continue;
            String name = component.getPackageName();
            if (info.baseIntent.hasCategory(Intent.CATEGORY_HOME)) continue;
            if (name.equals("com.tcl.android.launcher") || name.equals("local.tablet.navigation")) continue;
            if (name.equals(OWN_PACKAGE) && component.getClassName().endsWith(".PanelActivity")) continue;
            fresh.add(info.taskId);
        }
        @SuppressWarnings("unchecked")
        List<ActivityManager.RunningTaskInfo> running = (List<ActivityManager.RunningTaskInfo>)
            service.getMethod("getTasks", Integer.TYPE).invoke(tasks, 1);
        ActivityManager.RunningTaskInfo top = running.isEmpty() ? null : running.get(0);
        currentTask = top == null ? -1 : top.taskId;
        currentIsHome = top != null && top.baseIntent != null && top.baseIntent.hasCategory(Intent.CATEGORY_HOME);
        if (order.isEmpty()) {
            order.addAll(fresh);
        } else {
            order.retainAll(fresh);
            for (int i = fresh.size() - 1; i >= 0; i--) {
                if (!order.contains(fresh.get(i))) order.add(0, fresh.get(i));
            }
        }
        cursor = order.indexOf(currentTask);
    }

    synchronized String switchTask(int direction) throws Exception {
        if (direction != 1 && direction != -1) return "ERROR direction";
        if (!gestureActive) refreshOrder();
        Object tasks = activityTasks();
        Class<?> service = Class.forName("android.app.IActivityTaskManager");
        int index = cursor < 0 ? 0 : cursor + direction;
        if (index < 0 || index >= order.size()) return "BOUNDARY";
        int task = order.get(index);
        service.getMethod("startActivityFromRecents", Integer.TYPE, Bundle.class).invoke(tasks, task, null);
        cursor = index;
        return "OK task=" + task + " index=" + cursor;
    }
}
