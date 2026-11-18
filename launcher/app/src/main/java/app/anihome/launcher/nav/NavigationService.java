package app.anihome.launcher.nav;

import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

/**
 * AniHome gesture navigation (replaces the separate Edge Navigation app). A transparent 6 dp
 * strip on the bottom edge reads the gestures; a thin handle is drawn in a separate,
 * untouchable overlay so nothing is resized or reserved:
 *
 *  • swipe up → Home (the live app shrinks into Home with Shizuku)
 *  • swipe up and hold ~180 ms → Recents · from Home, any upward pull → Recents
 *  • drag sideways → previous/next app in a stable spatial order
 *  • double-tap → Back · single tap → shows the handle
 *
 * Runs in its own process (":nav") so a launcher crash never takes navigation down while
 * Android's navigation bar is hidden. Without Shizuku, Home, Back and Recents still work.
 * Supported on the TCL 9080G (Android 10), whose navigation bar it hides and restores.
 */
// Only ever started on Android 10 (see NavigationService.supported()); hidden APIs exist there.
@android.annotation.SuppressLint("NewApi")
public class NavigationService extends AccessibilityService implements DisplayManager.DisplayListener {
    static final String TAG = "AniHomeNav";
    /** TCL setting: 2 hides the system navigation bar without TCL's own gestures; 0 = buttons. */
    static final String TCL_NAVIGATION = "navigation_bar_gesture_enabled";
    static volatile NavigationService instance;

    final Handler handler = new Handler(Looper.getMainLooper());
    String homePackage = "app.anihome.launcher";
    int rotation = -1, appliedSize = -1, screenWidth, screenHeight;
    boolean homeHandleVisible, shuttingDown, homeInFront;
    EdgeView edge;
    FeedbackView feedback;
    TaskBridge tasks;
    RotationLock rotationLock;
    IBinder windowBinder;
    WindowManager wm;

    /** The tablet this was built and tested on; other devices keep Android's own navigation. */
    /** True while AniHome's gesture navigation is running (it then holds the rotation lock itself). */
    public static boolean running() { return instance != null; }

    public static boolean supported() {
        return Build.VERSION.SDK_INT == 29 && "9080G".equals(Build.MODEL);
    }

    final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (edge == null) return;
            edge.cancelGesture();
            edge.setVisibility(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) ? View.GONE : View.VISIBLE);
        }
    };

    int px(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override protected void onServiceConnected() {
        instance = this;
        shuttingDown = false;
        if (rotationLock != null) { rotationLock.close(); rotationLock = null; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!supported()) {
            Toast.makeText(this, "AniHome gestures aren't available on this device; Android's navigation stays on.", Toast.LENGTH_LONG).show();
            disableSelf();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
            ResolveInfo home = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            if (home != null && home.activityInfo != null) homePackage = home.activityInfo.packageName;
            windowBinder = ((IInterface) Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)).asBinder();
            Settings.Global.putInt(getContentResolver(), TCL_NAVIGATION, 2);
            // Shizuku's binder lives in AniHome's main process; ask for a copy for this one.
            new Thread(() -> {
                try { rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this); }
                catch (Exception failure) { Log.w(TAG, "Shizuku binder", failure); }
            }, "NavShizuku").start();
            tasks = new TaskBridge(this);
            tasks.listener = new TaskBridge.AnimationListener() {
                @Override public void completed(int outcome) {
                    if (outcome == 0 && edge != null) edge.pendingHome = false;
                    if (outcome == 4) performGlobalAction(GLOBAL_ACTION_RECENTS);
                    if (feedback != null && edge != null && !edge.touching) feedback.finishPreview();
                    Log.i(TAG, "Native gesture completed outcome=" + outcome);
                }
                @Override public void preview(Bitmap preview, String label, boolean available) {
                    if (feedback != null) feedback.setPreview(preview, label, available);
                    else if (preview != null) preview.recycle();
                }
            };
            rotationLock = new RotationLock(this, wm, handler);
            refresh();
            ((DisplayManager) getSystemService(DISPLAY_SERVICE)).registerDisplayListener(this, handler);
            registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
            Log.i(TAG, "Ready; fullscreen transparent edge");
        } catch (Exception failure) {
            fail(failure);
        }
    }

    /** Anything unexpected: put Android's navigation back and step aside. */
    void fail(Exception failure) {
        Log.e(TAG, "Setup failed", failure);
        Toast.makeText(this, "AniHome gestures couldn't start, so Android's navigation buttons are back. Set them up again in AniHome Settings → Navigation.", Toast.LENGTH_LONG).show();
        if (edge != null) {
            edge.cancelGesture();
            try { wm.removeViewImmediate(edge); } catch (Exception ignored) { /* not attached */ }
            edge = null;
        }
        restore();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) trackForeground(event);
        handler.postDelayed(this::updateHomeVisibility, 100);
        handler.postDelayed(this::updateHomeVisibility, 650);
        if (edge != null) {
            boolean locked = ((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();
            edge.setVisibility(locked ? View.GONE : View.VISIBLE);
        }
    }

    /** Without Shizuku, whether Home is in front comes from window changes instead. */
    private void trackForeground(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        String name = cls.toString();
        if (homePackage.contentEquals(pkg) && name.endsWith("HomeActivity")) homeInFront = true;
        else if (name.endsWith("Activity")) homeInFront = false;
    }

    @Override public void onInterrupt() { if (edge != null) edge.cancelGesture(); }

    @Override public void onDisplayAdded(int displayId) { }
    @Override public void onDisplayRemoved(int displayId) { }
    @Override public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) return;
        if (rotationLock != null) rotationLock.displayChanged();
        handler.post(this::refresh);
    }

    @Override public boolean onUnbind(Intent intent) { stop(); return super.onUnbind(intent); }
    @Override public void onDestroy() { stop(); super.onDestroy(); }

    /** (Re)creates the handle and the edge strip for the current screen size and rotation. */
    @SuppressWarnings("deprecation")
    void refresh() {
        if (shuttingDown || wm == null) return;
        try {
            Display display = wm.getDefaultDisplay();
            int current = display.getRotation();
            int strip = px(6f);
            if (rotation == current && appliedSize == strip && edge != null) return;
            rotation = current;
            appliedSize = strip;
            runOverscan("0,0,0,0");
            Point size = new Point();
            display.getRealSize(size);
            screenWidth = size.x;
            screenHeight = size.y;
            if (feedback != null) { wm.removeViewImmediate(feedback); feedback = null; }
            feedback = new FeedbackView(this);
            WindowManager.LayoutParams handleParams = new WindowManager.LayoutParams(size.x, size.y,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            handleParams.gravity = Gravity.TOP | Gravity.START;
            handleParams.setTitle("AniHome navigation handle");
            handleParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            wm.addView(feedback, handleParams);
            if (edge != null) { edge.cancelGesture(); wm.removeViewImmediate(edge); }
            edge = new EdgeView(this);
            WindowManager.LayoutParams edgeParams = new WindowManager.LayoutParams(size.x, strip,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | 0x02000000 /* FLAG_LAYOUT_IN_OVERSCAN */,
                PixelFormat.TRANSLUCENT);
            edgeParams.gravity = Gravity.TOP | Gravity.START;
            edgeParams.x = 0;
            edgeParams.y = size.y - strip;
            edgeParams.setTitle("AniHome navigation edge");
            edgeParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            edgeParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            wm.addView(edge, edgeParams);
            updateHomeVisibility();
            Log.i(TAG, "Layout rotation=" + current + " screen=" + size + " strip=" + strip);
        } catch (Exception failure) {
            fail(failure);
        }
    }

    /** Android 10 IWindowManager.setOverscan(display, l, t, r, b): no reserved edges. */
    void runOverscan(String value) throws Exception {
        int[] edges = new int[4];
        if (!"reset".equals(value)) {
            String[] parts = value.split(",");
            for (int i = 0; i < 4; i++) edges[i] = Integer.parseInt(parts[i]);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindowManager");
            data.writeInt(0);
            for (int edge : edges) data.writeInt(edge);
            if (!windowBinder.transact(14, data, reply, 0)) throw new IllegalStateException("Unsupported display service");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Android's navigation bar comes back whenever AniHome gestures stop. */
    void restore() {
        try { if (windowBinder != null) runOverscan("reset"); }
        catch (Exception failure) { Log.e(TAG, "Restore failed", failure); }
        try { Settings.Global.putInt(getContentResolver(), TCL_NAVIGATION, 0); }
        catch (Exception failure) { Log.e(TAG, "Restore buttons failed", failure); }
    }

    void stop() {
        if (shuttingDown) return;
        shuttingDown = true;
        handler.removeCallbacksAndMessages(null);
        try { ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(this); } catch (Exception ignored) { /* not registered */ }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { /* not registered */ }
        if (edge != null) {
            edge.cancelGesture();
            try { wm.removeViewImmediate(edge); } catch (Exception ignored) { /* gone */ }
            edge = null;
        }
        if (feedback != null) {
            try { wm.removeViewImmediate(feedback); } catch (Exception ignored) { /* gone */ }
            feedback = null;
        }
        if (rotationLock != null) rotationLock.close();
        if (tasks != null) tasks.close();
        if (supported()) restore();
        instance = null;
    }

    /** The handle stays on Home; in apps it shows while touched and fades ~2 s after release. */
    void updateHomeVisibility() {
        if (feedback == null) return;
        homeHandleVisible = tasks != null && tasks.ready() ? tasks.foregroundIsHome() : homeInFront;
        handler.removeCallbacks(feedback.hide);
        if (homeHandleVisible) feedback.fadeTo(1f, 120);
        else if (edge == null || !edge.touching) handler.postDelayed(feedback.hide, 1800);
    }

    // ---------------------------------------------------------------------------------------
    /** The transparent bottom strip that reads gestures. */
    final class EdgeView extends View {
        int direction; // 0 undecided, 1 up, 2 sideways
        long downTime, lastTapTime;
        float downX, downY, lastX, lastY, pauseX, pauseY, lastTapX;
        boolean fromHome, nativeStarted, pendingHome, touching, triggered, upwardStarted;
        int previewSign = 1;
        /**
         * Held still while pulling up (or pressing) → Recents. The timer confirms one frame later
         * so touches that queued up while the main thread was busy (a lifted finger) win.
         */
        final Runnable holdConfirm = () -> { if (touching && !triggered && direction != 2) act(GLOBAL_ACTION_RECENTS, "recents"); };
        final Runnable hold = () -> { if (touching && !triggered && direction != 2) handler.postDelayed(holdConfirm, 32); };
        void cancelHold() { handler.removeCallbacks(hold); handler.removeCallbacks(holdConfirm); }

        EdgeView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void act(int action, String name) {
            triggered = true;
            lastTapTime = 0;
            cancelHold();
            boolean success;
            if (action == GLOBAL_ACTION_RECENTS && nativeStarted) {
                tasks.animationEnd(1, 0);
                success = true;
            } else if (action == GLOBAL_ACTION_HOME && nativeStarted) {
                pendingHome = true;
                tasks.animationEnd(0, 0);
                success = true;
            } else {
                if (nativeStarted) tasks.animationEnd(3, 0);
                success = performGlobalAction(action);
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            Log.i(TAG, "Action " + name + " success=" + success);
        }

        void beginNative(int axis, int sign) {
            if (nativeStarted || tasks == null) return;
            nativeStarted = tasks.beginAnimation(axis, sign, screenWidth, screenHeight);
            fromHome = tasks.animationFromHome;
            Log.i(TAG, "Direct gesture started axis=" + axis + " native=" + nativeStarted + " fromHome=" + fromHome);
        }

        void cancelGesture() {
            cancelHold();
            if (nativeStarted && tasks != null) tasks.animationEnd(3, 0);
            touching = false;
            triggered = false;
            nativeStarted = false;
            pendingHome = false;
            if (feedback != null) feedback.clear();
        }

        void switchApp(int sign) {
            triggered = true;
            lastTapTime = 0;
            cancelHold();
            if (nativeStarted) {
                tasks.animationEnd(2, sign);
                Log.i(TAG, "Spatial switch requested " + sign);
            } else {
                String result = tasks == null ? "Unavailable" : tasks.switchTask(sign);
                Log.i(TAG, "Switch " + sign + " " + result);
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getRawX();
            float y = event.getRawY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) return false;
                    touching = true;
                    triggered = false;
                    nativeStarted = false;
                    pendingHome = false;
                    fromHome = false;
                    upwardStarted = false;
                    direction = 0;
                    pauseX = lastX = downX = x;
                    pauseY = lastY = downY = y;
                    downTime = SystemClock.uptimeMillis();
                    if (feedback != null) feedback.start();
                    handler.postDelayed(hold, 300);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!touching) return true;
                    lastX = x;
                    lastY = y;
                    float dx = x - downX;
                    float dy = downY - y;
                    if (feedback != null) feedback.move(dx, dy);
                    if (triggered) return true;
                    if (direction == 0 && Math.max(Math.abs(dx), Math.abs(dy)) >= px(3f)) {
                        cancelHold();
                        lastTapTime = 0;
                        direction = Math.abs(dx) > Math.abs(dy) ? 2 : 1;
                        previewSign = dx >= 0f ? 1 : -1;
                    }
                    if (direction == 1 && dy >= px(3f)) upwardStarted = true;
                    if (!nativeStarted && (direction == 2 || upwardStarted)) beginNative(direction, previewSign);
                    if (nativeStarted) tasks.animationMove(dx, Math.max(0f, dy));
                    if (direction == 2 && Math.abs(dx) > px(8f)) {
                        int sign = dx > 0f ? 1 : -1;
                        if (sign != previewSign) {
                            previewSign = sign;
                            tasks.previewDirection(previewSign);
                        }
                    }
                    // Pulling up: a short pause opens Recents; small wobbles don't restart the timer.
                    if (direction == 1 && dy >= px(6f) && Math.hypot(x - pauseX, y - pauseY) >= px(4f)) {
                        pauseX = x;
                        pauseY = y;
                        cancelHold();
                        handler.postDelayed(hold, 180);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    cancelHold();
                    int sign = x - downX >= 0f ? 1 : -1;
                    boolean switched = false;
                    if (touching && !triggered) {
                        float dx = x - downX;
                        float dy = downY - y;
                        if (direction == 2 && Math.abs(dx) >= px(24f) && feedback != null && feedback.available) {
                            switchApp(sign);
                            switched = true;
                        } else if (direction == 1 && fromHome && upwardStarted) {
                            act(GLOBAL_ACTION_RECENTS, "recents-from-home");
                        } else if (direction == 1 && dy >= px(6f)) {
                            act(GLOBAL_ACTION_HOME, "home");
                        } else if (direction == 0 && SystemClock.uptimeMillis() - downTime < 220) {
                            long now = SystemClock.uptimeMillis();
                            if (lastTapTime > 0 && now - lastTapTime < 300 && Math.abs(x - lastTapX) < px(48f)) {
                                lastTapTime = 0;
                                act(GLOBAL_ACTION_BACK, "back");
                            } else {
                                lastTapTime = now;
                                lastTapX = x;
                            }
                        } else if (nativeStarted) {
                            tasks.animationEnd(3, 0);
                        }
                    }
                    touching = false;
                    if (feedback != null) feedback.release(switched, sign);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (touching && !triggered && fromHome && upwardStarted) {
                        // From Home, a started pull always completes into Recents.
                        act(GLOBAL_ACTION_RECENTS, "recents-from-home-cancel");
                        touching = false;
                        if (feedback != null) feedback.release(false, 0);
                    } else {
                        cancelGesture();
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelGesture();
                    triggered = true;
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    /** Untouchable overlay: the rounded handle and the sideways neighbour preview. */
    final class FeedbackView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float alpha, x, y, pillX, previewAlpha;
        String label = "";
        boolean available = true;
        Bitmap preview;
        ValueAnimator fading, settling;
        final Runnable hide = () -> fadeTo(0f, 350);

        FeedbackView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void start() {
            handler.removeCallbacks(hide);
            if (settling != null) settling.cancel();
            if (preview != null) { preview.recycle(); preview = null; }
            previewAlpha = 0f; pillX = 0f; y = 0f; x = 0f;
            available = true;
            fadeTo(1f, 55);
        }

        void move(float dx, float dy) {
            // Sideways past the last app: the handle resists.
            if (edge != null && edge.direction == 2 && !available) dx *= 0.18f;
            x = dx;
            pillX = x;
            y = Math.max(0f, dy);
            previewAlpha = 1f;
            invalidate();
        }

        void release(boolean switched, int sign) {
            if (settling != null) settling.cancel();
            final float fromX = x, fromY = y;
            final float toX = switched ? (float) (sign * getWidth()) : 0f;
            settling = ValueAnimator.ofFloat(0f, 1f);
            settling.setDuration(180);
            settling.setInterpolator(new DecelerateInterpolator());
            settling.addUpdateListener(animation -> {
                float t = (Float) animation.getAnimatedValue();
                x = fromX + (toX - fromX) * t;
                pillX = fromX * (1f - t);
                y = fromY * (1f - t);
                invalidate();
            });
            settling.start();
            handler.removeCallbacks(hide);
            handler.postDelayed(hide, 1800);
            if (!switched) finishPreview();
        }

        void finishPreview() {
            if (preview == null) { previewAlpha = 0f; invalidate(); return; }
            handler.postDelayed(() -> {
                ValueAnimator fade = ValueAnimator.ofFloat(previewAlpha, 0f);
                fade.setDuration(150);
                fade.addUpdateListener(animation -> { previewAlpha = (Float) animation.getAnimatedValue(); invalidate(); });
                fade.start();
            }, 220);
        }

        void setPreview(Bitmap bitmap, String label, boolean available) {
            if (preview != null) preview.recycle();
            preview = bitmap;
            this.label = label == null ? "" : label;
            this.available = available;
            invalidate();
        }

        void fadeTo(float target, int duration) {
            if (target == 0f && homeHandleVisible) target = 1f;
            if (fading != null) fading.cancel();
            fading = ValueAnimator.ofFloat(alpha, target);
            fading.setDuration(duration);
            fading.addUpdateListener(animation -> { alpha = (Float) animation.getAnimatedValue(); invalidate(); });
            fading.start();
        }

        void clear() {
            handler.removeCallbacks(hide);
            if (fading != null) fading.cancel();
            if (settling != null) settling.cancel();
            previewAlpha = 0f; alpha = 0f; pillX = 0f; y = 0f; x = 0f;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (edge != null && edge.direction == 2 && Math.abs(x) > 1f && previewAlpha > 0f) {
                float left = x - (x > 0f ? 1f : -1f) * getWidth();
                RectF page = new RectF(left, 0f, left + getWidth(), getHeight());
                paint.setAlpha(Math.round(previewAlpha * 255f));
                if (preview != null && !preview.isRecycled()) {
                    canvas.drawBitmap(preview, null, page, paint);
                } else {
                    paint.setColor(available ? 0xFF151A24 : 0xFF101219);
                    paint.setAlpha(Math.round(previewAlpha * 255f));
                    canvas.drawRect(page, paint);
                    paint.setColor(0xFFFFFFFF);
                    paint.setTextSize(px(20f));
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(label, page.centerX(), page.centerY(), paint);
                }
            }
            // ~112 × 2 dp handle centred on the bottom edge; it stretches a little while pulled.
            float centerX = Math.max(px(65f), Math.min(getWidth() - px(65f), getWidth() / 2f + pillX * 0.55f));
            float centerY = Math.max(px(35f), getHeight() - px(3f) - y);
            float width = px(112f) + Math.min(px(24f), y * 0.08f);
            float height = px(2f) + Math.min(px(1.5f), y * 0.012f);
            RectF pill = new RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(px(1f));
            paint.setColor(0xFF000000);
            paint.setAlpha(Math.round(alpha * 130f));
            canvas.drawRoundRect(pill, height, height, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(alpha * 245f));
            canvas.drawRoundRect(pill, height, height, paint);
        }
    }
}
