package app.anihome.launcher.nav;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Client side of [TaskService]: binds the Shizuku user service (recovering Shizuku's binder if
 * this process missed it) and forwards gesture events. Every call is safe when unavailable.
 */
final class TaskBridge {
    interface AnimationListener {
        void preview(Bitmap preview, String label, boolean available);
        void completed(int outcome);
    }

    private static final String TAG = "AniHomeNav";
    final Context context;
    final Handler handler = new Handler(Looper.getMainLooper());
    AnimationListener listener;
    IBinder remote;
    boolean binding, closed, warmed, warming, animationFromHome;
    int retries;

    final Runnable timeout = this::onTimeout;
    final Shizuku.OnBinderReceivedListener received = this::bind;
    final Shizuku.OnBinderDeadListener dead = () -> { remote = null; binding = false; };
    final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> { if (result == 0) bind(); };
    final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = binder;
            binding = false;
            retries = 0;
            handler.removeCallbacks(timeout);
            Log.i(TAG, "Task switching ready");
            NavigationService service = NavigationService.instance;
            if (service != null) service.updateHomeVisibility();
        }
        @Override public void onServiceDisconnected(ComponentName name) { remote = null; binding = false; }
    };
    /** Callbacks from the service: 1 preview(available, label, bitmap) · 2 completed(outcome). */
    final Binder animationCallback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == 1) {
                boolean available = data.readInt() != 0;
                String label = data.readString();
                Bitmap bitmap = data.readParcelable(Bitmap.class.getClassLoader());
                handler.post(() -> {
                    if (listener != null && !closed) listener.preview(bitmap, label, available);
                    else if (bitmap != null) bitmap.recycle();
                });
                return true;
            }
            if (code == 2) {
                int outcome = data.readInt();
                handler.post(() -> { if (listener != null && !closed) listener.completed(outcome); });
                return true;
            }
            return false;
        }
    };
    final Shizuku.UserServiceArgs args;

    TaskBridge(Context context) {
        this.context = context;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, TaskService.class))
            .daemon(false).processNameSuffix("tasks").tag("anihome_nav_tasks").version(1);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }

    boolean ready() { return remote != null && remote.pingBinder(); }

    void bind() {
        try {
            if (closed || binding || warming || remote != null) return;
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != 0) return;
            if (!warmed) {
                // First use: make sure Shizuku's broker is healthy, then bind.
                warming = true;
                warmed = true;
                context.sendBroadcast(new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                    .setPackage("moe.shizuku.privileged.api").addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
                handler.postDelayed(() -> new Thread(() -> {
                    try { app.anihome.launcher.controls.ShizukuBroker.INSTANCE.refresh(); }
                    catch (Exception failure) { Log.w(TAG, "Broker recovery", failure); }
                    handler.post(() -> { warming = false; bind(); });
                }, "NavBroker").start(), 800);
                return;
            }
            binding = true;
            Shizuku.bindUserService(args, connection);
            handler.postDelayed(timeout, 5000);
        } catch (Exception failure) {
            binding = false;
            Log.e(TAG, "Task connection", failure);
        }
    }

    void onTimeout() {
        if (!binding || closed) return;
        try { Shizuku.unbindUserService(args, connection, true); } catch (Exception ignored) { /* stale */ }
        binding = false;
        warmed = false;
        if (retries++ < 2) bind();
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        try { Shizuku.unbindUserService(args, connection, true); } catch (Exception ignored) { /* not bound */ }
        remote = null;
    }

    boolean foregroundIsHome() {
        if (!ready()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskService.DESCRIPTOR);
            remote.transact(6, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception failure) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Starts the live animation; false (and a background bind) when the service isn't ready. */
    boolean beginAnimation(int axis, int sign, int width, int height) {
        animationFromHome = false;
        if (!ready()) { bind(); return false; }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskService.DESCRIPTOR);
            data.writeInt(axis);
            data.writeInt(sign);
            data.writeInt(width);
            data.writeInt(height);
            data.writeStrongBinder(animationCallback);
            remote.transact(2, data, reply, 0);
            reply.readException();
            String result = reply.readString();
            animationFromHome = "HOME".equals(result);
            return animationFromHome || "APP".equals(result);
        } catch (Exception failure) {
            Log.e(TAG, "Animation start", failure);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    void animationMove(float dx, float dy) {
        if (!ready()) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskService.DESCRIPTOR);
            data.writeFloat(dx);
            data.writeFloat(dy);
            remote.transact(3, data, null, Binder.FLAG_ONEWAY);
        } catch (Exception ignored) {
            /* Dropped frame; the next move or the end call catches up. */
        } finally {
            data.recycle();
        }
    }

    void animationEnd(int outcome, int direction) {
        if (!ready()) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskService.DESCRIPTOR);
            data.writeInt(outcome);
            data.writeInt(direction);
            remote.transact(4, data, null, Binder.FLAG_ONEWAY);
        } catch (Exception ignored) {
            /* Service gone; the gesture simply ends. */
        } finally {
            data.recycle();
        }
    }

    void previewDirection(int sign) {
        if (!ready()) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskService.DESCRIPTOR);
            data.writeInt(sign);
            remote.transact(5, data, null, Binder.FLAG_ONEWAY);
        } catch (Exception ignored) {
            /* Preview is optional. */
        } finally {
            data.recycle();
        }
    }

    String switchTask(int direction) {
        if (!ready()) { bind(); return "Shizuku access is needed for app switching"; }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TaskService.DESCRIPTOR);
            data.writeInt(direction);
            if (!remote.transact(1, data, reply, 0)) return "Task switching unavailable";
            reply.readException();
            return reply.readString();
        } catch (Exception failure) {
            return "ERROR " + failure;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
