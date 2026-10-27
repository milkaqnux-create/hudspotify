package pl.bossclient.media;

import net.minecraft.util.Identifier;

/** Aktualny stan odtwarzacza (wypełniany przez MediaProvider w osobnym wątku). */
public final class MediaState {
    public static final Identifier COVER = Identifier.of("bossclient", "media_cover");

    public static volatile boolean present = false;
    public static volatile boolean hasCover = false;
    public static volatile boolean playing = false;
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile double pos = 0, dur = 0;
    public static volatile long stamp = 0;

    /** Pozycja w sekundach, płynnie interpolowana między odczytami. */
    public static double position() {
        double p = pos;
        if (playing) p += (System.currentTimeMillis() - stamp) / 1000.0;
        return dur > 0 ? Math.min(p, dur) : p;
    }

    private MediaState() {
    }
}
