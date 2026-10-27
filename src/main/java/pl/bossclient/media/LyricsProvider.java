package pl.bossclient.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zsynchronizowane teksty (LRC) z lrclib.net - linijka pod nazwą artysty. */
public final class LyricsProvider {
    public record Line(double t, String s) {
    }

    public static volatile List<Line> lines = List.of();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern LRC = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)]\\s*(.*)$");
    private static volatile int gen = 0;

    public static void request(String title, String artist, double dur) {
        final int my = ++gen;
        lines = List.of();
        Thread t = new Thread(() -> {
            try {
                String synced = fetchGet(title, artist, dur);
                if (synced == null) synced = fetchSearch(title, artist);
                if (synced == null || my != gen) return;
                List<Line> parsed = parse(synced);
                if (my == gen) lines = parsed;
            } catch (Exception ignored) {
            }
        }, "BossClient-Lyrics");
        t.setDaemon(true);
        t.start();
    }

    /** Aktualna linijka dla pozycji p (sekundy). */
    public static String current(double p) {
        String cur = "";
        for (Line l : lines) {
            if (l.t() <= p) cur = l.s();
            else break;
        }
        return cur;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "BossClient Media HUD")
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return res.statusCode() == 200 ? res.body() : null;
    }

    private static String fetchGet(String title, String artist, double dur) throws Exception {
        String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                + (dur > 0 ? "&duration=" + (int) Math.round(dur) : "");
        String body = get(url);
        if (body == null) return null;
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonElement e = o.get("syncedLyrics");
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String fetchSearch(String title, String artist) throws Exception {
        String body = get("https://lrclib.net/api/search?q=" + enc(artist + " " + title));
        if (body == null) return null;
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        for (JsonElement el : arr) {
            JsonElement e = el.getAsJsonObject().get("syncedLyrics");
            if (e != null && !e.isJsonNull() && !e.getAsString().isBlank()) return e.getAsString();
        }
        return null;
    }

    private static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = LRC.matcher(raw.trim());
            if (!m.matches()) continue;
            double t = Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2));
            out.add(new Line(t, m.group(3).trim()));
        }
        out.sort((a, b) -> Double.compare(a.t(), b.t()));
        return out;
    }

    private LyricsProvider() {
    }
}
