package pl.bossclient.media;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Ustawienia media playera. Zapis: .minecraft/config/bossclient_media.json */
public final class MediaSettings {
    /** Podepnij pod swój moduł "Media" w GUI BossClienta (włącz/wyłącz). "Usuń" w menu ustawia to na false. */
    public boolean enabled = true;
    public boolean centeredGrowth = false;
    public boolean lyrics = true;
    public float scale = 1.0f;     // 0.5 - 2.0
    public float rounding = 9.0f;  // 0 - 10
    public float x = -1f, y = -1f; // -1 = domyślna pozycja (środek, dół ekranu)

    private static MediaSettings inst = new MediaSettings();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bossclient_media.json");
    }

    public static MediaSettings get() {
        return inst;
    }

    public static void load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                MediaSettings s = GSON.fromJson(Files.readString(p), MediaSettings.class);
                if (s != null) inst = s;
            }
        } catch (Exception e) {
            inst = new MediaSettings();
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(inst));
        } catch (Exception ignored) {
        }
    }
}
