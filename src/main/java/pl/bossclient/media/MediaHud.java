package pl.bossclient.media;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Media player HUD.
 * - karta rysowana zawsze na HUD-zie
 * - po otwarciu czatu (T): LPM + przeciąganie = zmiana pozycji
 * - po otwarciu czatu: PPM na HUD = menu (Centered Growth, Lyrics, Scale, Rounding, Usuń)
 */
public final class MediaHud {
    // rozmiar karty i menu w jednostkach GUI (przy scale = 1.0)
    public static final int W = 132, H = 44;
    public static final int MW = 158, MH = 80;
    private static final int SX = 80, SW = 32;          // pozycja i szerokość suwaków w menu
    private static final float ROW0 = 16f, ROWH = 12.5f; // początek i wysokość wierszy menu

    private static final int ACCENT = 0xFFA48CF5;
    private static final int WHITE = 0xFFFFFFFF;

    private static boolean menuOpen, dragging;
    private static float menuX, menuY, dragDX, dragDY;
    private static int sliderDrag = -1; // 2 = scale, 3 = rounding

    public static void init() {
        MediaSettings.load();
        MediaProvider.start();

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;
            drawCard(ctx, mc.currentScreen instanceof ChatScreen);
        });

        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
            if (!(screen instanceof ChatScreen)) return;
            // zwracamy false = anulujemy kliknięcie w czacie, gdy trafiło w nasz HUD/menu
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, btn) -> !onClick(mx, my, btn));
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, td) -> {
                pollDrag();
                drawMenu(ctx);
            });
            ScreenEvents.remove(screen).register(s -> {
                menuOpen = false;
                dragging = false;
                sliderDrag = -1;
                MediaSettings.save();
            });
        });
    }

    // ------------------------------------------------------------------ geometria

    /** {x, y, w, h} karty po uwzględnieniu skali i Centered Growth. */
    private static float[] rect() {
        MinecraftClient mc = MinecraftClient.getInstance();
        MediaSettings s = MediaSettings.get();
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        if (s.x < 0) {
            s.x = (sw - W) / 2f;
            s.y = sh - 100f;
        }
        float w = W * s.scale, h = H * s.scale;
        float ox = s.x, oy = s.y;
        if (s.centeredGrowth) {
            ox -= (w - W) / 2f;
            oy -= (h - H) / 2f;
        }
        return new float[]{ox, oy, w, h};
    }

    /** Ustawia widoczny lewy górny róg karty (odwrotność rect()). */
    private static void setOrigin(float ox, float oy) {
        MediaSettings s = MediaSettings.get();
        float w = W * s.scale, h = H * s.scale;
        s.x = s.centeredGrowth ? ox + (w - W) / 2f : ox;
        s.y = s.centeredGrowth ? oy + (h - H) / 2f : oy;
    }

    private static boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ karta

    private static void drawCard(DrawContext c, boolean chatOpen) {
        MediaSettings s = MediaSettings.get();
        if (!s.enabled) return;
        boolean present = MediaState.present;
        if (!present && !chatOpen) return; // w czacie pokazujemy zawsze, żeby dało się ustawić pozycję

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        float[] r = rect();

        var m = c.getMatrices();
        m.push();
        m.translate(r[0], r[1], 0f);
        m.scale(s.scale, s.scale, 1f);

        // tło + delikatna ramka
        rrect(c, -0.5f, -0.5f, W + 1, H + 1, s.rounding + 0.5f, 0x22FFFFFF);
        rrect(c, 0, 0, W, H, s.rounding, 0xEB101012);

        // okładka
        if (present && MediaState.hasCover) {
            c.drawTexture(RenderLayer::getGuiTextured, MediaState.COVER, 8, 8, 0f, 0f, 17, 17, 64, 64, 64, 64);
        } else {
            rrect(c, 8, 8, 17, 17, 3f, 0xFFE9E9EC);
        }

        String title = present ? MediaState.title : "Brak muzyki";
        String artist = present ? MediaState.artist : "Nic nie gra";
        double dur = present ? MediaState.dur : 0;
        double pos = present ? MediaState.position() : 0;

        txt(c, tr, title, 32, 13f, 0.8f, WHITE, true, 94f, false);
        txt(c, tr, artist, 32, 18.5f, 0.65f, 0xFF77777D, false, 94f, false);

        if (s.lyrics && present) {
            String lyric = LyricsProvider.current(pos);
            if (!lyric.isEmpty()) txt(c, tr, lyric, 32, 24.5f, 0.62f, 0xFFD6D6DA, false, 94f, false);
        }

        // czasy i pasek
        txt(c, tr, fmt(pos), 6.5f, 31.5f, 0.6f, 0xFF8E8E93, false, 40f, false);
        txt(c, tr, fmt(dur), W - 6.5f, 31.5f, 0.6f, 0xFF8E8E93, false, 40f, true);
        float bx = 6.5f, bw = W - 13f;
        rrect(c, bx, 35.5f, bw, 2f, 1f, 0xFF3A3A40);
        float frac = dur > 0 ? (float) Math.min(1.0, pos / dur) : 0f;
        if (frac > 0f) rrect(c, bx, 35.5f, Math.max(2f, bw * frac), 2f, 1f, ACCENT);

        m.pop();
    }

    // ------------------------------------------------------------------ menu

    private static void drawMenu(DrawContext c) {
        if (!menuOpen) return;
        MediaSettings s = MediaSettings.get();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        var m = c.getMatrices();
        m.push();
        m.translate(menuX, menuY, 300f);

        rrect(c, -0.5f, -0.5f, MW + 1, MH + 1, 8.5f, 0x26FFFFFF);
        rrect(c, 0, 0, MW, MH, 8f, 0xF2161618);

        txt(c, tr, "Media", 8, 9f, 0.65f, 0xFF8E8E93, false, 100f, false);

        String[] names = {"Centered Growth", "Lyrics", "Scale", "Rounding", "Usuń"};
        for (int i = 0; i < names.length; i++) {
            float cy = ROW0 + 6f + i * ROWH;
            int col = i == 4 ? 0xFFE5534B : 0xFFEDEDF0;
            // tu możesz podmienić na własne ikonki (drawTexture) - na razie kolorowy placeholder
            rrect(c, 8, cy - 3.5f, 7, 7, 2f, i == 4 ? 0xFFE5534B : 0xFF8F7BE0);
            txt(c, tr, names[i], 22, cy, 0.75f, col, false, 70f, false);
        }

        toggle(c, ROW0 + 6f, s.centeredGrowth);
        toggle(c, ROW0 + 6f + ROWH, s.lyrics);
        slider(c, tr, ROW0 + 6f + ROWH * 2, (s.scale - 0.5f) / 1.5f, fmt2(s.scale));
        slider(c, tr, ROW0 + 6f + ROWH * 3, s.rounding / 10f, fmt2(s.rounding));

        m.pop();
    }

    private static void toggle(DrawContext c, float cy, boolean on) {
        float x = MW - 8 - 18;
        rrect(c, x, cy - 4.5f, 18, 9, 4.5f, on ? ACCENT : 0xFF3A3A3F);
        rrect(c, on ? x + 18 - 8 : x + 1, cy - 3.5f, 7, 7, 3.5f, on ? WHITE : 0xFFB4B4B9);
    }

    private static void slider(DrawContext c, TextRenderer tr, float cy, float t, String label) {
        t = Math.max(0f, Math.min(1f, t));
        rrect(c, SX, cy - 1f, SW, 2f, 1f, 0xFF3A3A3F);
        rrect(c, SX, cy - 1f, Math.max(1f, SW * t), 2f, 1f, ACCENT);
        rrect(c, SX + SW * t - 3f, cy - 3f, 6, 6, 3f, 0xFFCDBFFF);
        txt(c, tr, label, MW - 8, cy, 0.75f, 0xFFB8B8BD, false, 30f, true);
    }

    // ------------------------------------------------------------------ interakcja

    /** @return true jeśli kliknięcie zostało zużyte (nie przekazujemy go czatowi). */
    private static boolean onClick(double mx, double my, int btn) {
        MediaSettings s = MediaSettings.get();
        if (!s.enabled) return false;

        if (menuOpen) {
            if (in(mx, my, menuX, menuY, MW, MH)) {
                if (btn == 0) menuClick(mx, my);
                return true;
            }
            menuOpen = false;
            MediaSettings.save();
        }

        float[] r = rect();
        if (in(mx, my, r[0], r[1], r[2], r[3])) {
            if (btn == 0) {
                dragging = true;
                dragDX = (float) mx - r[0];
                dragDY = (float) my - r[1];
            } else if (btn == 1) {
                MinecraftClient mc = MinecraftClient.getInstance();
                int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
                menuX = Math.max(0, Math.min((float) mx, sw - MW));
                menuY = Math.max(0, Math.min((float) my, sh - MH));
                menuOpen = true;
            }
            return true;
        }
        return false;
    }

    private static void menuClick(double mx, double my) {
        MediaSettings s = MediaSettings.get();
        int row = (int) Math.floor((my - menuY - ROW0) / ROWH);
        switch (row) {
            case 0 -> {
                float[] r = rect();
                s.centeredGrowth = !s.centeredGrowth;
                setOrigin(r[0], r[1]); // karta zostaje wizualnie w tym samym miejscu
                MediaSettings.save();
            }
            case 1 -> {
                s.lyrics = !s.lyrics;
                MediaSettings.save();
            }
            case 2, 3 -> {
                if (mx >= menuX + SX - 8) {
                    sliderDrag = row;
                    applySlider(mx);
                }
            }
            case 4 -> {
                s.enabled = false; // "Usuń" - wyłącza moduł (włączasz z powrotem w GUI klienta)
                menuOpen = false;
                MediaSettings.save();
            }
            default -> {
            }
        }
    }

    private static void applySlider(double mx) {
        MediaSettings s = MediaSettings.get();
        float v = (float) Math.max(0.0, Math.min(1.0, (mx - (menuX + SX)) / SW));
        if (sliderDrag == 2) {
            float[] r = rect();
            s.scale = Math.round((0.5f + v * 1.5f) * 100f) / 100f;
            if (s.centeredGrowth) setOrigin(r[0], r[1]); // zachowaj środek
        } else if (sliderDrag == 3) {
            s.rounding = Math.round(v * 10f * 100f) / 100f;
        }
    }

    /** Przeciąganie i suwaki obsługujemy pollingiem stanu myszy (działa niezależnie od wersji API). */
    private static void pollDrag() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!down) {
            if (dragging || sliderDrag >= 0) MediaSettings.save();
            dragging = false;
            sliderDrag = -1;
            return;
        }
        double mx = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();

        if (dragging) {
            float[] r = rect();
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            float nx = Math.max(0, Math.min((float) mx - dragDX, sw - r[2]));
            float ny = Math.max(0, Math.min((float) my - dragDY, sh - r[3]));
            setOrigin(nx, ny);
        }
        if (sliderDrag >= 0) applySlider(mx);
    }

    // ------------------------------------------------------------------ helpery rysowania

    /** Zaokrąglony prostokąt - rysowany w 4x wyższej rozdzielczości, żeby rogi nie były "schodkowe". */
    static void rrect(DrawContext c, float x, float y, float w, float h, float r, int col) {
        final int K = 4;
        var m = c.getMatrices();
        m.push();
        m.scale(1f / K, 1f / K, 1f);
        int X = Math.round(x * K), Y = Math.round(y * K), Wd = Math.round(w * K), Ht = Math.round(h * K);
        int R = Math.min(Math.round(r * K), Math.min(Wd, Ht) / 2);
        if (R <= 0) {
            c.fill(X, Y, X + Wd, Y + Ht, col);
        } else {
            for (int i = 0; i < R; i++) {
                double dy = R - i - 0.5;
                int inset = R - (int) Math.round(Math.sqrt((double) R * R - dy * dy));
                c.fill(X + inset, Y + i, X + Wd - inset, Y + i + 1, col);
                c.fill(X + inset, Y + Ht - i - 1, X + Wd - inset, Y + Ht - i, col);
            }
            c.fill(X, Y + R, X + Wd, Y + Ht - R, col);
        }
        m.pop();
    }

    /** Tekst o skali sc, wyśrodkowany w pionie na cy; obcinany do maxW; opcjonalnie wyrównany do prawej. */
    private static void txt(DrawContext c, TextRenderer tr, String str, float x, float cy, float sc,
                            int col, boolean bold, float maxW, boolean right) {
        if (str == null || str.isEmpty()) return;
        Style st = bold ? Style.EMPTY.withBold(true) : Style.EMPTY;
        Text t = Text.literal(str).setStyle(st);
        while (str.length() > 1 && tr.getWidth(t) * sc > maxW) {
            str = str.substring(0, str.length() - 1);
            t = Text.literal(str).setStyle(st);
        }
        float w = tr.getWidth(t) * sc;
        var m = c.getMatrices();
        m.push();
        m.translate(right ? x - w : x, cy - 3.5f * sc, 0f);
        m.scale(sc, sc, 1f);
        c.drawText(tr, t, 0, 0, col, false);
        m.pop();
    }

    private static String fmt(double sec) {
        int t = (int) Math.max(0, sec);
        return String.format("%02d:%02d", t / 60, t % 60);
    }

    private static String fmt2(float v) {
        return String.format("%.2f", v).replace('.', ',');
    }

    private MediaHud() {
    }
}
