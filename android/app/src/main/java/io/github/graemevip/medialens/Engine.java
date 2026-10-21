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

  static synchronized void init(Context c) throws Exception {
    if (ready) return;
    if (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) > 4096)
      throw new IOException(
          "This Android beta needs a 4 KB-page device. The bundled FFmpeg is awaiting an upstream"
              + " 16 KB compatibility fix.");
    YoutubeDL.getInstance().init(c.getApplicationContext());
    FFmpeg.getInstance().init(c.getApplicationContext());
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
    q.addOption("--socket-timeout", 30);
    q.addOption("--no-mtime");
    return q;
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
    if (s.length() > 1800) s = s.substring(s.length() - 1800);
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
