package app.anihome.launcher.nav;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Live task animation for gesture navigation. Runs inside the Shizuku user service: it starts
 * Android's recents animation with itself as the runner, receives the live task surfaces and
 * moves/scales them with the finger, then finishes into Home, Recents or back to the app.
 * Ported from the tested Edge Navigation 3.3 behaviour.
 */
// Only ever started on Android 10 (see NavigationService.supported()); hidden APIs exist there.
@android.annotation.SuppressLint("NewApi")
final class NativeAnimation extends Binder {
    static final String RUNNER = "android.view.IRecentsAnimationRunner";
    private static final String TAG = "AniHomeNavNative";

    final HandlerThread thread = new HandlerThread("NavSurfaces");
    final Handler handler;
    final ArrayList<Object> targets = new ArrayList<>();
    Object controller;
    boolean active, ending, homeTarget;
    int axis, task, width, height;
    float dx, dy, homeExit;
    long updated;
    /** Ends a gesture the system or the client forgot about. */
    final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (SystemClock.uptimeMillis() - updated > 5000) { finishNow(false, null); return; }
            handler.postDelayed(this, 1000);
        }
    };

    NativeAnimation() {
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    static Object invoke(Object target, String type, String name, Class<?>[] types, Object... args) throws Exception {
        return Class.forName(type).getMethod(name, types).invoke(target, args);
    }

    void begin(int width, int height, int task, int axis, boolean fromHome) {
        handler.post(() -> {
            finishNow(false, null);
            this.width = width; this.height = height; this.task = task; this.axis = axis;
            dx = 0f; dy = 0f; homeExit = 0f;
            homeTarget = axis == 1 && !fromHome;
            active = true; ending = false;
            updated = SystemClock.uptimeMillis();
            try {
                Object runner = invoke(null, RUNNER + "$Stub", "asInterface", new Class<?>[]{IBinder.class}, this);
                Object tasks = invoke(null, "android.app.ActivityTaskManager", "getService", new Class<?>[0]);
                ComponentName component = recentsComponent();
                if (homeTarget) {
                    Object packages = invoke(null, "android.app.ActivityThread", "getPackageManager", new Class<?>[0]);
                    Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                    ResolveInfo info = (ResolveInfo) invoke(packages, "android.content.pm.IPackageManager", "resolveIntent",
                        new Class<?>[]{Intent.class, String.class, Integer.TYPE, Integer.TYPE}, query, null, 0x10000, 0);
                    if (info == null || info.activityInfo == null) throw new IllegalStateException("Default home unavailable");
                    component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                }
                Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (homeTarget) intent.addCategory(Intent.CATEGORY_HOME);
                invoke(tasks, "android.app.IActivityTaskManager", "startRecentsActivity",
                    new Class<?>[]{Intent.class, Class.forName("android.app.IAssistDataReceiver"), Class.forName(RUNNER)}, intent, null, runner);
                Log.i(TAG, "Begin task=" + task + " axis=" + axis + " target=" + (homeTarget ? "HOME" : "RECENTS"));
                handler.postDelayed(watchdog, 1000);
            } catch (Exception failure) {
                Log.e(TAG, "Begin", failure);
                active = false;
            }
        });
    }

    /** The system's own Recents screen (the device launcher's quickstep on most phones). */
    static ComponentName recentsComponent() {
        try {
            android.content.res.Resources system = android.content.res.Resources.getSystem();
            int id = system.getIdentifier("config_recentsComponentName", "string", "android");
            if (id != 0) {
                ComponentName parsed = ComponentName.unflattenFromString(system.getString(id));
                if (parsed != null) return parsed;
            }
        } catch (Exception ignored) { /* Fall back to the tested TCL component. */ }
        return new ComponentName("com.tcl.android.launcher", "com.android.quickstep.RecentsActivity");
    }

    void move(float x, float y) {
        handler.post(() -> {
            if (!active || ending) return;
            dx = x; dy = y; updated = SystemClock.uptimeMillis();
            apply();
        });
    }

    /** outcome: 0 home, 1 recents, 2 switch (direction ±1), 3 cancel. [done] runs after finishing. */
    void end(int outcome, int direction, Runnable done) {
        handler.post(() -> {
            if (!active) { if (done != null) done.run(); return; }
            ending = true;
            updated = SystemClock.uptimeMillis();
            final float sx = dx, sy = dy;
            final float ex = outcome == 2 ? (float) (width * direction) : 0f;
            final float ey = (outcome == 1 || outcome == 0) ? height * 0.45f : 0f;
            final long start = SystemClock.uptimeMillis();
            handler.post(new Runnable() {
                @Override public void run() {
                    float t = Math.min(1f, (SystemClock.uptimeMillis() - start) / 180f);
                    float eased = 1f - (float) Math.pow(1f - t, 3.0);
                    dx = sx + (ex - sx) * eased;
                    dy = sy + (ey - sy) * eased;
                    homeExit = homeTarget && outcome == 0 ? eased : 0f;
                    apply();
                    if (t < 1f && active) { handler.postDelayed(this, 16); return; }
                    finishNow(outcome == 1 || outcome == 0, done);
                }
            });
        });
    }

    void close() {
        handler.post(() -> { finishNow(false, null); thread.quitSafely(); });
    }

    void finishNow(boolean toTarget, Runnable done) {
        handler.removeCallbacks(watchdog);
        Object current = controller;
        controller = null;
        active = false; ending = false;
        targets.clear();
        if (current != null) {
            try {
                invoke(current, "android.view.IRecentsAnimationController", "finish",
                    new Class<?>[]{Boolean.TYPE, Boolean.TYPE}, toTarget, false);
            } catch (Exception failure) { Log.w(TAG, "Finish", failure); }
        }
        if (done != null) done.run();
    }

    /** One frame: the app follows the finger, shrinking (up) or sliding (sideways). */
    void apply() {
        if (controller == null) return;
        try {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            for (Object target : targets) {
                Class<?> type = target.getClass();
                if (type.getField("taskId").getInt(target) != task) continue;
                SurfaceControl leash = (SurfaceControl) type.getField("leash").get(target);
                Point position = (Point) type.getField("position").get(target);
                float progress = axis == 1
                    ? Math.min(1f, Math.max(0f, dy) / (height * 0.32f))
                    : Math.min(1f, Math.abs(dx) / width);
                float scale = axis == 1 ? 1f - 0.24f * progress : 1f - 0.035f * progress;
                scale += (0.12f - scale) * homeExit;
                float x = position.x + (width - width * scale) / 2f + (axis == 2 ? dx : dx * 0.08f);
                float y = position.y + (axis == 1 ? height * 0.07f * progress : height * (1f - scale) / 2f);
                y += (height * 0.84f - y) * homeExit;
                transaction.setAlpha(leash, 1f - homeExit);
                invoke(transaction, "android.view.SurfaceControl$Transaction", "setMatrix",
                    new Class<?>[]{SurfaceControl.class, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE}, leash, scale, 0f, 0f, scale);
                transaction.setPosition(leash, x, y);
                invoke(transaction, "android.view.SurfaceControl$Transaction", "setCornerRadius",
                    new Class<?>[]{SurfaceControl.class, Float.TYPE}, leash, progress * 36f);
            }
            transaction.apply();
            transaction.close();
        } catch (Exception failure) {
            Log.w(TAG, "Frame", failure);
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(RUNNER); return true; }
        data.enforceInterface(RUNNER);
        if (code == 2) { // onAnimationCanceled
            data.readInt();
            handler.post(() -> { controller = null; targets.clear(); active = false; Log.i(TAG, "Canceled by system"); });
            return true;
        }
        if (code == 3) { // onAnimationStart(controller, targets, …)
            try {
                Object controller = invoke(null, "android.view.IRecentsAnimationController$Stub", "asInterface",
                    new Class<?>[]{IBinder.class}, data.readStrongBinder());
                @SuppressWarnings("unchecked")
                Parcelable.Creator<Object> creator = (Parcelable.Creator<Object>) Class.forName("android.view.RemoteAnimationTarget").getField("CREATOR").get(null);
                Object[] apps = data.createTypedArray(creator);
                handler.post(() -> {
                    if (!active) {
                        try {
                            invoke(controller, "android.view.IRecentsAnimationController", "finish",
                                new Class<?>[]{Boolean.TYPE, Boolean.TYPE}, false, false);
                        } catch (Exception ignored) { /* Already gone. */ }
                        return;
                    }
                    this.controller = controller;
                    targets.clear();
                    Collections.addAll(targets, apps);
                    apply();
                    Log.i(TAG, "Live surfaces ready targets=" + apps.length);
                });
            } catch (Exception failure) {
                Log.e(TAG, "Callback", failure);
            }
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
