package app.anihome.launcher.nav;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

/**
 * Keeps Android's Auto-rotate tile the source of truth. Turning it off locks the angle that is
 * on screen at that moment (portrait or either landscape), across Home and app switches, by
 * holding an invisible 1×1 window that requests that orientation. Turning it on releases it.
 * This stops the tablet snapping back to its default landscape when auto-rotate is off.
 */
public final class RotationLock {
    private static final String TAG = "AniHomeRotation";
    final Context context;
    final WindowManager windows;
    final Handler handler;
    final ContentObserver observer;
    View anchor;
    boolean automatic, closed;
    int lastFreeRotation, lockedRotation;

    @SuppressWarnings("deprecation")
    public RotationLock(Context context, WindowManager windows, Handler handler) {
        this.context = context;
        this.windows = windows;
        this.handler = handler;
        automatic = readAuto();
        lastFreeRotation = windows.getDefaultDisplay().getRotation();
        lockedRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, lastFreeRotation);
        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) { settingsChanged(uri); }
        };
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer);
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION), false, observer);
        if (!automatic) applyLock(lockedRotation);
        Log.i(TAG, "Started auto=" + automatic + " displayed=" + lastFreeRotation + " saved=" + lockedRotation);
    }

    boolean readAuto() {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) != 0;
    }

    @SuppressWarnings("deprecation")
    public void displayChanged() {
        if (closed) return;
        if (automatic && readAuto()) lastFreeRotation = windows.getDefaultDisplay().getRotation();
    }

    @SuppressWarnings("deprecation")
    void settingsChanged(Uri uri) {
        if (closed) return;
        boolean auto = readAuto();
        if (auto != automatic) {
            automatic = auto;
            if (auto) {
                removeAnchor();
                lastFreeRotation = windows.getDefaultDisplay().getRotation();
                Log.i(TAG, "Auto enabled; released lock");
            } else {
                lockedRotation = lastFreeRotation;
                try { Settings.System.putInt(context.getContentResolver(), Settings.System.USER_ROTATION, lockedRotation); }
                catch (Exception failure) { Log.w(TAG, "Saving the angle needs Modify system settings", failure); }
                applyLock(lockedRotation);
                Log.i(TAG, "Auto disabled; preserving angle=" + lockedRotation);
            }
        } else if (!automatic && Settings.System.getUriFor(Settings.System.USER_ROTATION).equals(uri)) {
            int angle = Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, lockedRotation);
            if (angle != lockedRotation) {
                lockedRotation = angle;
                applyLock(angle);
                Log.i(TAG, "Explicit lock angle=" + angle);
            }
        }
    }

    void applyLock(int rotation) {
        int orientation;
        switch (rotation) {
            case Surface.ROTATION_90: orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case Surface.ROTATION_180: orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case Surface.ROTATION_270: orientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        // An accessibility overlay outranks apps' own orientation requests (verified with the
        // camera) without the "displaying over other apps" notification an app overlay causes.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.alpha = 0f;
        params.screenOrientation = orientation;
        params.setTitle("AniHome rotation lock");
        try {
            if (anchor == null) {
                anchor = new View(context);
                windows.addView(anchor, params);
            } else {
                windows.updateViewLayout(anchor, params);
            }
        } catch (Exception failure) {
            Log.e(TAG, "Lock unavailable", failure);
        }
    }

    void removeAnchor() {
        if (anchor == null) return;
        try { windows.removeViewImmediate(anchor); } catch (Exception ignored) { /* already gone */ }
        anchor = null;
    }

    public void close() {
        closed = true;
        context.getContentResolver().unregisterContentObserver(observer);
        removeAnchor();
    }
}
