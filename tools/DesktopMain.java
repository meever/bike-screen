import io.github.bikescreen.dlna.*;
import java.net.*;
import java.util.*;

/** Runs the renderer on a desktop JVM with a fake player: cast to it and watch what the phone sends. */
public class DesktopMain {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.err.println("usage: DesktopMain <this machine's LAN IPv4>"); System.exit(2); }
        InetAddress ip = InetAddress.getByName(a[0]);
        Player fake = new Player() {
            volatile String state = "NO_MEDIA_PRESENT"; volatile long startedAt, pos; volatile int vol = 50;
            public void load(String uri, String title) { state = "PLAYING"; pos = 0; startedAt = System.currentTimeMillis(); }
            public void play() { if (!state.equals("PLAYING")) { startedAt = System.currentTimeMillis() - pos; state = "PLAYING"; } }
            public void pause() { pos = positionMs(); state = "PAUSED_PLAYBACK"; }
            public void stop() { state = "STOPPED"; pos = 0; }
            public void seek(long ms) { pos = ms; startedAt = System.currentTimeMillis() - ms; }
            public void setVolume(int p) { vol = p; }
            public String state() { return state; }
            public long positionMs() { return state.equals("PLAYING") ? System.currentTimeMillis() - startedAt : pos; }
            public long durationMs() { return state.equals("NO_MEDIA_PRESENT") ? 0 : 120_000; }
            public int volume() { return vol; }
        };
        new Renderer("bike-dlna-test", UUID.nameUUIDFromBytes("desktop".getBytes()).toString(), fake, ip,
                m -> System.out.println(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + " " + m)).start();
        Thread.currentThread().join();
    }
}
