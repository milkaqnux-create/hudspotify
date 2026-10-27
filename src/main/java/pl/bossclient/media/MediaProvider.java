package pl.bossclient.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Czyta to, co aktualnie gra w systemie (Spotify, YouTube w przeglądarce, itp.)
 * przez Windows Global System Media Transport Controls. Tylko Windows.
 */
public final class MediaProvider {
    private static boolean started = false;

    private static final String SCRIPT = """
            [System.Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::InvariantCulture
            [Console]::OutputEncoding=[System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0]
            function Await($op,$type){$t=$asTask.MakeGenericMethod($type).Invoke($null,@($op));$t.Wait(-1)|Out-Null;$t.Result}
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
            [void][Windows.Storage.Streams.IRandomAccessStreamReference,Windows.Storage.Streams,ContentType=WindowsRuntime]
            [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
            $mgr=Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
            $last=''
            while($true){
              try{
                $s=$mgr.GetCurrentSession()
                if($s -eq $null){
                  [Console]::Out.WriteLine('{"present":false}')
                } else {
                  $p=Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                  $tl=$s.GetTimelineProperties()
                  $pb=$s.GetPlaybackInfo()
                  $playing=($pb.PlaybackStatus -eq 'Playing')
                  $pos=$tl.Position.TotalSeconds
                  if($playing){$pos+=([DateTimeOffset]::UtcNow-$tl.LastUpdatedTime).TotalSeconds}
                  $dur=$tl.EndTime.TotalSeconds-$tl.StartTime.TotalSeconds
                  $key=[string]$p.Title+'|'+[string]$p.Artist
                  $changed=($key -ne $last)
                  $thumb=''
                  if($changed){
                    $last=$key
                    try{
                      $ref=$p.Thumbnail
                      if($ref -ne $null){
                        $st=Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                        $net=[System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st)
                        $ms=New-Object System.IO.MemoryStream
                        $net.CopyTo($ms)
                        $thumb=[Convert]::ToBase64String($ms.ToArray())
                      }
                    }catch{}
                  }
                  $o=@{present=$true;title=[string]$p.Title;artist=[string]$p.Artist;pos=[math]::Round($pos,2);dur=[math]::Round($dur,2);playing=$playing;changed=$changed;cover=$thumb}
                  [Console]::Out.WriteLine(($o|ConvertTo-Json -Compress))
                }
              }catch{ [Console]::Out.WriteLine('{"present":false}') }
              [Console]::Out.Flush()
              Start-Sleep -Milliseconds 500
            }
            """;

    public static synchronized void start() {
        if (started) return;
        started = true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Thread t = new Thread(MediaProvider::loop, "BossClient-Media");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            Process proc = null;
            try {
                String enc = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
                proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc)
                        .redirectErrorStream(false).start();
                proc.getOutputStream().close();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) handle(line.trim());
                }
            } catch (Exception ignored) {
            } finally {
                if (proc != null) proc.destroyForcibly();
            }
            MediaState.present = false;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void handle(String line) {
        if (line.isEmpty() || line.charAt(0) != '{') return;
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (!o.get("present").getAsBoolean()) {
                MediaState.present = false;
                return;
            }
            String title = o.get("title").getAsString();
            String artist = o.get("artist").getAsString();
            double dur = o.get("dur").getAsDouble();
            boolean changed = o.has("changed") && o.get("changed").getAsBoolean();

            MediaState.title = title;
            MediaState.artist = artist;
            MediaState.pos = o.get("pos").getAsDouble();
            MediaState.dur = dur;
            MediaState.playing = o.get("playing").getAsBoolean();
            MediaState.stamp = System.currentTimeMillis();
            MediaState.present = true;

            if (changed) {
                loadCover(o.has("cover") ? o.get("cover").getAsString() : "");
                LyricsProvider.request(title, artist, dur);
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadCover(String b64) {
        if (b64 == null || b64.isEmpty()) {
            MediaState.hasCover = false;
            return;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
            if (src == null) {
                MediaState.hasCover = false;
                return;
            }
            // kadrowanie do kwadratu i skalowanie do 64x64
            int side = Math.min(src.getWidth(), src.getHeight());
            BufferedImage sq = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            var g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(sq, 0, 0, 64, 64, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            byte[] png = bos.toByteArray();

            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                try {
                    NativeImage ni = NativeImage.read(new ByteArrayInputStream(png));
                    NativeImageBackedTexture tex = new NativeImageBackedTexture(ni);
                    tex.setFilter(true, false);
                    mc.getTextureManager().registerTexture(MediaState.COVER, tex);
                    MediaState.hasCover = true;
                } catch (Exception e) {
                    MediaState.hasCover = false;
                }
            });
        } catch (Exception e) {
            MediaState.hasCover = false;
        }
    }

    private MediaProvider() {
    }
}
