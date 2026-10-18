package io.github.bikescreen;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;

/** Full-screen video surface for whatever BikeService is playing; tap for controls. Closes itself when casting stops. */
public final class PlayerActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private SurfaceView surface;
    private FrameLayout root;
    private float aspect;
    private LinearLayout bar;
    private TextView pauseBtn, speedBtn, holdBtn;
    private long hideAt, titleUntil;
    private TextView titleView, status;
    private long stoppedSince;              // 0 while playing; finish after 4 s stopped, so the phone's next video reuses the screen
    private String shownTitle;
    private static Typeface cjk;            // bundled Noto Sans SC subset: the device may have no CJK font

    private final Player.Listener sizes = new Player.Listener() {
        @Override public void onVideoSizeChanged(VideoSize v) {
            if (v.width > 0 && v.height > 0) { aspect = v.width * v.pixelWidthHeightRatio / v.height; fit(); }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        BikeService.start(this);
        if (BikeService.stopped) {          // opened from the launcher: just make sure the receiver runs
            Toast.makeText(this, BikeService.NAME + " ready to cast", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());
        root.setOnClickListener(v -> showBar(bar.getVisibility() != View.VISIBLE));
        bar = new LinearLayout(this);
        bar.setBackgroundColor(0xB0000000);
        bar.setGravity(Gravity.CENTER);
        button("-10s", () -> BikeService.skip(-10_000));
        pauseBtn = button("Pause", BikeService::togglePause);
        button("+10s", () -> BikeService.skip(10_000));
        speedBtn = button("1x", BikeService::cycleSpeed);
        holdBtn = button("Hold: off", () -> { if (BikeService.self != null) BikeService.self.setHold(!BikeService.hold); });
        button("Next", BikeService::next);
        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (cjk == null) cjk = Typeface.createFromAsset(getAssets(), "title.otf");
        titleView = new TextView(this);
        titleView.setTypeface(cjk);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(24);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(32, 20, 32, 20);
        titleView.setBackgroundColor(0xB0000000);
        titleView.setVisibility(View.GONE);
        root.addView(titleView, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(30);
        status.setPadding(40, 20, 40, 20);
        status.setBackgroundColor(0xB0000000);
        status.setText("Loading…");
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        immersive();
    }

    @Override protected void onStart() {
        super.onStart();
        if (BikeService.exo == null || surface == null) return;
        BikeService.exo.setVideoSurfaceView(surface);
        BikeService.exo.addListener(sizes);
        VideoSize v = BikeService.exo.getVideoSize();
        if (v.width > 0) sizes.onVideoSizeChanged(v);
        watch();
    }

    @Override protected void onStop() {
        h.removeCallbacksAndMessages(null);
        if (BikeService.exo != null && surface != null) {
            BikeService.exo.removeListener(sizes);
            BikeService.exo.clearVideoSurfaceView(surface);
        }
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (BikeService.exo != null) { BikeService.stopped = true; BikeService.exo.stop(); }
        finish();
    }

    @Override public void onWindowFocusChanged(boolean f) { if (f) immersive(); }

    private void watch() {
        long now = SystemClock.uptimeMillis();
        if (!BikeService.stopped) stoppedSince = 0;
        else if (stoppedSince == 0) stoppedSince = now;
        else if (now - stoppedSince > 4_000) { finish(); return; }
        String st = BikeService.self == null ? "" : BikeService.self.state();
        status.setVisibility(BikeService.stopped || st.equals("TRANSITIONING") ? View.VISIBLE : View.GONE);
        String t = BikeService.title;
        if (!t.equals(shownTitle)) { shownTitle = t; titleView.setText(clean(t)); titleUntil = SystemClock.uptimeMillis() + 8_000; }
        boolean showTitle = !t.isEmpty() && (bar.getVisibility() == View.VISIBLE || SystemClock.uptimeMillis() < titleUntil);
        titleView.setVisibility(showTitle ? View.VISIBLE : View.GONE);
        if (bar.getVisibility() == View.VISIBLE) {
            if (SystemClock.uptimeMillis() > hideAt) showBar(false);
            else labels();
        }
        h.postDelayed(this::watch, 300);
    }

    /** Drops trailing #hashtags and @mentions unless they are all there is. */
    static String clean(String t) {
        String c = t.replaceAll("[#@]\\S+", "").replaceAll("\\s+", " ").trim();
        return c.isEmpty() ? t : c;
    }

    private TextView button(String label, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(26);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 36, 0, 36);
        b.setOnClickListener(v -> {
            if (BikeService.exo != null) action.run();
            showBar(true);
        });
        bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private void showBar(boolean on) {
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        hideAt = SystemClock.uptimeMillis() + 6_000;
        if (on) labels();
    }

    private void labels() {
        if (BikeService.exo == null) return;
        pauseBtn.setText(BikeService.exo.getPlayWhenReady() ? "Pause" : "Play");
        float sp = BikeService.exo.getPlaybackParameters().speed;
        speedBtn.setText((sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "x");
        holdBtn.setText(!BikeService.hold ? "Hold: off" : BikeService.pending != null ? "Hold: 1 waiting" : "Hold: on");
    }

    /** Letterbox the surface to the video's aspect ratio. */
    private void fit() {
        if (aspect <= 0 || root.getWidth() == 0) return;
        int W = root.getWidth(), H = root.getHeight();
        int w = W, hgt = Math.round(W / aspect);
        if (hgt > H) { hgt = H; w = Math.round(H * aspect); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != w || lp.height != hgt) { lp.width = w; lp.height = hgt; surface.setLayoutParams(lp); }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
