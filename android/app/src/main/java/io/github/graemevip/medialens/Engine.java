package io.github.graemevip.medialens;

import android.content.Context;
import android.net.Uri;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

final class Engine {
  private static boolean ready;
  private static File pluginDirectory;

  static synchronized void init(Context c) throws Exception {
    if (ready) return;
    if (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) > 4096)
      throw new IOException(
          "This Android beta needs a 4 KB-page device. The bundled FFmpeg is awaiting an upstream"
              + " 16 KB compatibility fix.");
    YoutubeDL.getInstance().init(c.getApplicationContext());
    FFmpeg.getInstance().init(c.getApplicationContext());
    pluginDirectory = new File(c.getNoBackupFilesDir(), "network-plugins");
    File plugin = new File(pluginDirectory, "medialens/yt_dlp_plugins/extractor/medialens_dns.py");
    if (!plugin.getParentFile().mkdirs() && !plugin.getParentFile().isDirectory())
      throw new IOException("Cannot initialise download networking.");
    try (InputStream in = c.getAssets().open("network/medialens_dns.py");
        OutputStream out = new FileOutputStream(plugin)) {
      byte[] bytes = new byte[8192];
      int count;
      while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
    }
    ready = true;
  }

  static String url(String text) {
    String s = text == null ? "" : text.trim();
    java.util.regex.Matcher match =
        java.util.regex.Pattern.compile(
                "https?://[^\\s<>]+", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(s);
    if (match.find()) s = match.group();
    while (s.endsWith(")") || s.endsWith("]") || s.endsWith("."))
      s = s.substring(0, s.length() - 1);
    Uri u = Uri.parse(s);
    if (!("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
        || u.getHost() == null
        || u.getUserInfo() != null
        || s.matches("(?s).*[\\p{Cntrl}\\s].*"))
      throw new IllegalArgumentException("Paste a valid http or https media link.");
    return s;
  }

  static YoutubeDLRequest request(String url) {
    YoutubeDLRequest q = new YoutubeDLRequest(url);
    q.addOption("--no-playlist");
    if (pluginDirectory != null) q.addOption("--plugin-dirs", pluginDirectory.getAbsolutePath());
    q.addOption("--socket-timeout", 20);
    q.addOption("--retries", 2);
    q.addOption("--extractor-retries", 1);
    q.addOption("--fragment-retries", 1);
    q.addOption("--abort-on-unavailable-fragments");
    q.addOption("-S", "res,proto");
    q.addOption("--no-mtime");
    return q;
  }

  static String format(String quality, boolean direct) {
    if ("audio".equals(quality))
      return direct
          ? "ba[protocol=https]/b[protocol=https]/ba[protocol=http]/b[protocol=http]"
          : "bestaudio/best";
    String filter = "best".equals(quality) ? "" : "[height<=?" + Integer.parseInt(quality) + "]";
    if (!direct) return "bv*" + filter + "+ba/b" + filter;
    return "bv*[protocol=https]"
        + filter
        + "+ba[protocol=https]/b[protocol=https]"
        + filter
        + "/bv*[protocol=http]"
        + filter
        + "+ba[protocol=http]/b[protocol=http]"
        + filter;
  }

  static boolean connectionFailure(Throwable error) {
    String text = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return text.contains("no address associated")
        || text.contains("resolve host")
        || text.contains("name or service not known")
        || text.contains("temporary failure in name")
        || text.contains("timed out")
        || text.contains("connection reset");
  }

  static JSONObject info(Context c, String url) throws Exception {
    String path = Uri.parse(url).getPath();
    if (path != null
        && path.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|gif|webp|avif|bmp)$")) {
      JSONObject o = new JSONObject();
      o.put("title", Uri.parse(url).getLastPathSegment());
      o.put("image", true);
      return o;
    }
    init(c);
    YoutubeDLRequest q = request(url);
    q.addOption("--dump-single-json");
    q.addOption("--skip-download");
    String out = YoutubeDL.getInstance().execute(q).getOut();
    JSONObject result = new JSONObject(out);
    if (result.optString("title").isEmpty())
      throw new IOException("This page did not expose downloadable media.");
    return result;
  }

  static String friendly(Throwable e) {
    String s = e.getMessage();
    if (s == null || s.isBlank()) s = e.getClass().getSimpleName();
    s = s.replaceAll("(?i)(cookie|authorization|token)=[^\\s]+", "$1=[hidden]");
    LinkedHashSet<String> lines = new LinkedHashSet<>(Arrays.asList(s.split("\\R")));
    s = String.join("\n", lines);
    if (s.length() > 2400) s = s.substring(0, 900) + "\n…\n" + s.substring(s.length() - 1500);
    return s;
  }

  static void delete(File f) {
    if (f.isDirectory()) {
      File[] a = f.listFiles();
      if (a != null) for (File child : a) delete(child);
    }
    f.delete();
  }
}
