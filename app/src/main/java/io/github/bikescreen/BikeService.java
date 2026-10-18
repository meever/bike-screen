package io.github.bikescreen;

import android.app.*;
import android.content.*;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import io.github.bikescreen.dlna.Renderer;
import java.net.*;
import java.util.*;
import java.util.concurrent.FutureTask;

/**
 * Foreground service: owns the ExoPlayer and the DLNA renderer, so the bike is castable
 * whenever it is on. PlayerActivity only attaches a surface to {@link #exo}.
 */
public final class BikeService extends Service implements io.github.bikescreen.dlna.Player {
    /** Name shown in phones' cast lists; set friendlyName in local.properties to override. */
    static final String NAME = BuildConfig.FRIENDLY_NAME;
    static final String TAG = "bike-dlna";

    /** Player gain for casts at full phone volume; Douyin's SetVolume scales it down from here. */
    static final float CAST_GAIN = 0.55f;
    /** Default lift for everything the bike plays (Jellyfin included), in millibels; 0 = off.
     *  Change live: curl http://<bike>:49152/cmd/boost?mb=800 (debugEndpoints builds only) or
     *  adb shell am broadcast -a io.github.bikescreen.BOOST --ei mb 800 -p io.github.bikescreen */
    static final int BOOST_MB = 600;

    static ExoPlayer exo;                   // main-thread only
    static volatile boolean stopped = true; // true → PlayerActivity finishes
    /** Hold: the phone's skips wait until the current video ends; the latest one is kept in {@link #pending}. */
    static volatile boolean hold;
    static volatile String pending, pendingTitle;
    /** Title of what is playing, from the phone's DIDL metadata; "" when it sent none. */
    static volatile String title = "";
    static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f};

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread net;
    private Handler netH;
    private Renderer renderer;
    private InetAddress ip;
    private WifiManager.MulticastLock mcast;
    private WifiManager.WifiLock wifi;
    private PowerManager.WakeLock wake;

    // state cached on the main thread, read from network threads
    private volatile String state = "NO_MEDIA_PRESENT";
    private volatile long pos, dur;
    private volatile int castVolume = 100;
    private String reported = "";           // last state written to the log (main thread)

    // ------------------------------------------------ loudness boost (main thread)

    private int boostMb;
    /** Audio session → enhancer. Session 0 is the global output mix; others come from apps' OPEN_AUDIO_EFFECT broadcasts. */
    private final Map<Integer, LoudnessEnhancer> boosts = new HashMap<>();

    private final BroadcastReceiver effects = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if ("io.github.bikescreen.BOOST".equals(a)) { setBoost(i.getIntExtra("mb", BOOST_MB)); return; }
            int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
            String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (session <= 0) return;
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(a)) { log("session " + session + " opened by " + pkg); attach(session); }
            else { LoudnessEnhancer e = boosts.remove(session); if (e != null) e.release(); log("session " + session + " closed by " + pkg); }
        }
    };

    private void attach(int session) {
        if (boostMb <= 0 || boosts.containsKey(session)) return;
        try {
            LoudnessEnhancer e = new LoudnessEnhancer(session);
            e.setTargetGain(boostMb);
            e.setEnabled(true);
            boosts.put(session, e);
            log("boost +" + boostMb / 100f + " dB on session " + session + ": enabled=" + e.getEnabled());
        } catch (Throwable t) { log("boost on session " + session + " failed: " + t); }
    }

    private void setBoost(int mb) {
        boostMb = Math.max(0, Math.min(2000, mb));
        getSharedPreferences("bike", MODE_PRIVATE).edit().putInt("boostMb", boostMb).apply();
        if (boostMb == 0) { for (LoudnessEnhancer e : boosts.values()) e.release(); boosts.clear(); }
        else for (LoudnessEnhancer e : boosts.values()) e.setTargetGain(boostMb);
        attach(0);
        if (exo != null) exo.setVolume(castGain() * castVolume / 100f);
        log("boost set to " + boostMb + " mB");
    }

    private String boostStatus() {
        StringBuilder s = new StringBuilder("boost " + boostMb + " mB\n");
        for (Map.Entry<Integer, LoudnessEnhancer> e : boosts.entrySet())
            s.append("session ").append(e.getKey()).append(" enabled=").append(e.getValue().getEnabled())
             .append(" gain=").append(e.getValue().getTargetGain()).append("\n");
        return s.toString();
    }

    /** Casts stay at CAST_GAIN net: the global boost lifts them too, so take it back out here. */
    private float castGain() {
        return boosts.containsKey(0) ? CAST_GAIN / (float) Math.pow(10, boostMb / 2000.0) : CAST_GAIN;
    }

    /** GET /cmd/boost[?mb=N] from the renderer's HTTP port. */
    private String command(String cmd) {
        if (!cmd.startsWith("boost")) return "unknown command\n";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("mb=(\\d+)").matcher(cmd);
        Integer mb = m.find() ? Integer.valueOf(m.group(1)) : null;
        FutureTask<String> t = new FutureTask<>(() -> { if (mb != null) setBoost(mb); return boostStatus(); });
        main.post(t);
        try { return t.get(); } catch (Exception e) { return e + "\n"; }
    }

    static void log(String m) { Log.w(TAG, m); Renderer.note(m); }

    static void start(Context c) {
        Intent i = new Intent(c, BikeService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        self = this;
        startForeground(1, notification());
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        mcast = wm.createMulticastLock(TAG); mcast.setReferenceCounted(false); mcast.acquire();
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG); wifi.acquire();
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wake.acquire();

        exo = new ExoPlayer.Builder(this)
                .setLoadControl(new DefaultLoadControl.Builder()
                        // start after 0.5 s of video instead of the default 2.5 s
                        .setBufferDurationsMs(15_000, 60_000, 500, 1_500).build())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(5_000).setReadTimeoutMs(10_000)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        exo.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) { log("playback: " + e.getErrorCodeName() + " " + e.getCause()); }
        });
        IntentFilter f = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction("io.github.bikescreen.BOOST");
        registerReceiver(effects, f);
        setBoost(getSharedPreferences("bike", MODE_PRIVATE).getInt("boostMb", BOOST_MB));
        tick();

        net = new HandlerThread("net"); net.start();
        netH = new Handler(net.getLooper());
        netH.post(this::watchNetwork);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        netH.removeCallbacksAndMessages(null);
        if (renderer != null) renderer.stop();
        net.quit();
        main.removeCallbacksAndMessages(null);
        exo.release(); exo = null;
        self = null;
        unregisterReceiver(effects);
        for (LoudnessEnhancer e : boosts.values()) e.release();
        mcast.release(); wifi.release(); wake.release();
        super.onDestroy();
    }

    /** (Re)starts the renderer whenever the Wi-Fi address appears or changes. */
    private void watchNetwork() {
        InetAddress now = wifiAddress();
        if (now != null && !now.equals(ip)) {
            if (renderer != null) renderer.stop();
            ip = now;
            renderer = new Renderer(NAME, uuid(), this, ip, m -> Log.w(TAG, m));
            renderer.setCommand(this::command);
            renderer.setDebugEndpoints(BuildConfig.DEBUG_ENDPOINTS);
            try { renderer.start(); } catch (Exception e) { log("renderer start: " + e); renderer = null; ip = null; }
        }
        netH.postDelayed(this::watchNetwork, 10_000);
    }

    private static InetAddress wifiAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || !n.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a;
            }
        } catch (SocketException ignored) {}
        return null;
    }

    private String uuid() {
        SharedPreferences p = getSharedPreferences("bike", MODE_PRIVATE);
        String u = p.getString("uuid", null);
        if (u == null) { u = UUID.randomUUID().toString(); p.edit().putString("uuid", u).apply(); }
        return u;
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(TAG, "Cast receiver", NotificationManager.IMPORTANCE_MIN);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            b = new Notification.Builder(this, TAG);
        } else b = new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(NAME + " cast receiver").build();
    }

    // ------------------------------------------------ Player (called on network threads)

    /** Mirrors ExoPlayer state into volatile fields every 200 ms. */
    private void tick() {
        if (exo == null) return;
        int s = exo.getPlaybackState();
        long d = exo.getDuration();
        pos = exo.getCurrentPosition();
        dur = d == C.TIME_UNSET ? 0 : d;
        if (stopped) state = exo.getMediaItemCount() == 0 ? "NO_MEDIA_PRESENT" : "STOPPED";
        else if (s == Player.STATE_ENDED && pending != null) { log("held video ended; playing the phone's latest"); startPending(); }
        else if (s == Player.STATE_ENDED || s == Player.STATE_IDLE) { state = "STOPPED"; stopped = true; }
        else if (!exo.getPlayWhenReady()) state = "PAUSED_PLAYBACK";
        else state = s == Player.STATE_READY ? "PLAYING" : "TRANSITIONING";
        if (!state.equals(reported)) {
            reported = state;
            log("state " + state + " at " + pos / 100 / 10f + "s of " + dur / 100 / 10f + "s");
        }
        main.postDelayed(this::tick, 200);
    }

    /** True while hold should keep the current video against the phone's Stop/next. */
    private boolean holding() { return hold && !stopped && exo != null && exo.getMediaItemCount() > 0; }

    @Override public void load(String uri, String t) {
        if (holding()) { pendingTitle = t; pending = uri; log("hold: phone's next video queued"); return; }
        state = "TRANSITIONING";
        main.post(() -> start(uri, t));
    }

    private void startPending() { String u = pending, t = pendingTitle; pending = null; start(u, t); }

    private void start(String uri, String t) {
        stopped = false;
        title = t;
        state = "TRANSITIONING";
        exo.setMediaItem(MediaItem.fromUri(uri));
        exo.prepare();
        exo.setPlayWhenReady(true);
        startActivity(new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    // ------------------------------------------------ on-screen controls (main thread)

    static void togglePause() { exo.setPlayWhenReady(!exo.getPlayWhenReady()); }

    static void skip(long ms) { exo.seekTo(Math.max(0, exo.getCurrentPosition() + ms)); }

    /** Jump to the end: plays the held video, else reports STOPPED so the phone sends its next one. */
    static void next() {
        long d = exo.getDuration();
        log("next pressed at " + exo.getCurrentPosition() / 1000 + "s");
        if (d != C.TIME_UNSET) exo.seekTo(d);
    }

    static float cycleSpeed() {
        float now = exo.getPlaybackParameters().speed, next = SPEEDS[0];
        for (int i = 0; i < SPEEDS.length - 1; i++) if (now == SPEEDS[i]) next = SPEEDS[i + 1];
        exo.setPlaybackSpeed(next);
        log("speed " + next + "x");
        return next;
    }

    /** Turning hold off plays whatever the phone queued meanwhile. */
    void setHold(boolean on) {
        hold = on;
        log("hold " + (on ? "on" : "off"));
        if (!on && pending != null) startPending();
    }

    static BikeService self;                // set while the service runs; main-thread use only

    @Override public void play() {
        if (holding() && pending != null) return;   // the Play that follows a queued skip
        if (state.equals("PAUSED_PLAYBACK")) state = "PLAYING";
        main.post(() -> {
            if (stopped && exo.getMediaItemCount() > 0) { stopped = false; exo.seekTo(0); exo.prepare(); }
            exo.setPlayWhenReady(true);
        });
    }

    @Override public void pause() { state = "PAUSED_PLAYBACK"; main.post(() -> exo.setPlayWhenReady(false)); }

    @Override public void stop() {
        if (holding()) { log("hold: ignored phone's Stop"); return; }
        state = "STOPPED";
        main.post(() -> { stopped = true; exo.stop(); });
    }

    @Override public void seek(long ms) { pos = ms; main.post(() -> exo.seekTo(ms)); }

    /** Phone volume only scales this player's gain; the bike's system volume is never touched. */
    @Override public void setVolume(int percent) {
        castVolume = Math.max(0, Math.min(100, percent));
        log("volume " + castVolume + "%");
        main.post(() -> { if (exo != null) exo.setVolume(castGain() * castVolume / 100f); });
    }

    @Override public int volume() { return castVolume; }

    @Override public String state() { return state; }
    @Override public long positionMs() { return pos; }
    @Override public long durationMs() { return dur; }
}
