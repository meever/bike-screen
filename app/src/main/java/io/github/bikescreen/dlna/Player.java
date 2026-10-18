package io.github.bikescreen.dlna;

/** What the renderer needs from a video player. Calls arrive on network threads. */
public interface Player {
    void load(String uri, String title);   // start buffering + playing as soon as possible; title may be empty
    void play();
    void pause();
    void stop();
    void seek(long ms);
    void setVolume(int percent);

    /** UPnP TransportState: NO_MEDIA_PRESENT, STOPPED, TRANSITIONING, PLAYING, PAUSED_PLAYBACK */
    String state();
    long positionMs();
    long durationMs();
    int volume();
}
