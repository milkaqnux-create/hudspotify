# Media player HUD (Fabric 1.21.4, Yarn)

Pliki: `src/main/java/pl/bossclient/media/`

| Plik | Co robi |
|---|---|
| `MediaHud.java` | rysowanie karty, przeciąganie w czacie, menu pod PPM |
| `MediaProvider.java` | odczyt tego co gra w Windowsie (Spotify / przeglądarka / itp.), okładka |
| `LyricsProvider.java` | zsynchronizowane teksty z lrclib.net (linijka pod artystą) |
| `MediaSettings.java` | zapis ustawień w `config/bossclient_media.json` |
| `MediaState.java` | aktualny stan utworu |

## Integracja

1. Skopiuj pakiet `pl.bossclient.media` do projektu (fork SoupAPI).
2. W `onInitializeClient()` dodaj: `pl.bossclient.media.MediaHud.init();`
3. Wymagane: Fabric API (`HudRenderCallback`, `ScreenEvents`, `ScreenMouseEvents`).
4. Moduł włączasz/wyłączasz przez `MediaSettings.get().enabled` - podepnij pod swój toggle w GUI.
   Przycisk "Usuń" w menu ustawia to na `false`.

## Obsługa

- Otwórz czat (T): LPM na karcie + przeciąganie = zmiana pozycji
- Otwórz czat, PPM na karcie = menu: Centered Growth, Lyrics, Scale, Rounding, Usuń
- Scale 0.50-2.00, Rounding 0-10

## Uwagi

- Odczyt utworu działa tylko na Windows 10/11 (SMTC przez `powershell.exe`).
- Czcionka to domyślna czcionka Minecraft; żeby wyglądało 1:1 jak na screenach, podmień
  `Style` w `txt()` na własną czcionkę (`Style.EMPTY.withFont(Identifier.of(...))`).
- Ikonki w menu to kolorowe placeholdery (`rrect` w `drawMenu`) - podmień na `drawTexture`.
