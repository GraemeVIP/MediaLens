package io.github.graemevip.medialens;

import android.content.Context;
import android.net.*;
import com.yausername.youtubedl_android.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Compares Android and native-runtime DNS without requesting media bytes. */
final class ConnectionCheck {
  static String run(Context context) throws Exception {
    Context c = context.getApplicationContext();
    Engine.init(c);
    JSONObject failed = null;
    JSONArray jobs = Jobs.all(c);
    for (int i = 0; i < jobs.length(); i++) {
      JSONObject j = jobs.optJSONObject(i);
      if (j != null && "failed".equals(j.optString("status")) && !j.optBoolean("image")) {
        failed = j;
        break;
      }
    }
    if (failed == null) return "There are no failed video downloads to check.";
    ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
    Network network = cm.getActiveNetwork();
    NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
    StringBuilder report =
        new StringBuilder("VPN: ")
            .append(
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ? "connected"
                    : "not detected");
    report.append("\nEngine: ").append(YoutubeDL.getInstance().versionName(c));
    Set<String> hosts = new LinkedHashSet<>();
    host(hosts, failed.optString("url"));
    try {
      YoutubeDLRequest request = Engine.request(Engine.url(failed.optString("url")));
      request.addOption("--dump-single-json");
      request.addOption("--skip-download");
      request.addOption("--retries", 1);
      request.addOption("--extractor-retries", 1);
      String quality = failed.optString("quality", "best");
      request.addOption("-f", Engine.format(quality, false));
      JSONObject info = new JSONObject(YoutubeDL.getInstance().execute(request).getOut());
      host(hosts, info.optString("url"));
      if (info.optString("protocol").contains("m3u8")) inspectManifest(info, hosts, report);
      JSONArray selected = info.optJSONArray("requested_formats");
      if (selected != null)
        for (int i = 0; i < selected.length(); i++) {
          JSONObject f = selected.optJSONObject(i);
          if (f != null) {
            host(hosts, f.optString("url"));
            host(hosts, f.optString("manifest_url"));
            if (f.optString("protocol").contains("m3u8")) inspectManifest(f, hosts, report);
          }
        }
      JSONArray formats = info.optJSONArray("formats");
      if (formats != null)
        for (int i = formats.length() - 1; i >= 0 && hosts.size() < 5; i--) {
          JSONObject f = formats.optJSONObject(i);
          if (f != null) host(hosts, f.optString("url"));
        }
      report.append("\nPage metadata: available");
      Map<String, Integer> transports = new LinkedHashMap<>();
      if (formats != null)
        for (int i = 0; i < formats.length(); i++) {
          JSONObject f = formats.optJSONObject(i);
          if (f != null) {
            String p = f.optString("protocol", "unknown");
            transports.put(p, transports.getOrDefault(p, 0) + 1);
          }
        }
      report.append("\nAvailable formats: ").append(transports);
      Set<String> variants = new HashSet<>();
      if (formats != null)
        for (int i = formats.length() - 1; i >= 0 && variants.size() < 4; i--) {
          JSONObject f = formats.optJSONObject(i);
          if (f == null || !f.optString("protocol").contains("m3u8")) continue;
          String h = new URI(f.optString("url")).getHost();
          String key = h + " / " + f.optString("vcodec");
          if (variants.add(key)) {
            report.append("\n\nStream variant: ").append(key);
            inspectManifest(f, hosts, report);
          }
        }
      if (selected != null)
        for (int i = 0; i < selected.length(); i++) {
          JSONObject f = selected.optJSONObject(i);
          if (f != null)
            report
                .append("\nSelected transport: ")
                .append(f.optString("protocol"))
                .append(" · ")
                .append(f.optInt("height"))
                .append("p");
        }
      else report.append("\nSelected transport: ").append(info.optString("protocol"));
    } catch (Exception e) {
      report.append("\nPage metadata: ").append(clean(Engine.friendly(e)));
    }
    JSONArray nativeResult = nativeDns(c, hosts);
    ExecutorService javaResolver = Executors.newCachedThreadPool();
    try {
      for (String hostname : hosts) {
        report.append("\n\n").append(hostname);
        Future<Boolean> result =
            javaResolver.submit(() -> InetAddress.getAllByName(hostname).length > 0);
        try {
          boolean resolved = result.get(5, TimeUnit.SECONDS);
          report.append("\nAndroid DNS: ").append(resolved ? "resolved" : "no address");
        } catch (Exception e) {
          result.cancel(true);
          report.append("\nAndroid DNS: lookup failed");
        }
        for (int i = 0; i < nativeResult.length(); i++) {
          JSONObject row = nativeResult.optJSONObject(i);
          if (row != null && hostname.equals(row.optString("host"))) {
            report
                .append("\nDownloader DNS with fallback: ")
                .append(row.optString("any", "unknown"));
            report.append("\nDownloader IPv4: ").append(row.optString("ipv4", "unknown"));
          }
        }
      }
    } finally {
      javaResolver.shutdownNow();
    }
    report.append("\n\nMetadata and manifest text were checked. No video was downloaded.");
    return report.toString();
  }

  private static void inspectManifest(JSONObject format, Set<String> hosts, StringBuilder report) {
    HttpURLConnection conn = null;
    try {
      URI address = new URI(format.getString("url"));
      if (!"https".equals(address.getScheme()) && !"http".equals(address.getScheme())) return;
      conn = (HttpURLConnection) address.toURL().openConnection();
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(15000);
      JSONObject headers = format.optJSONObject("http_headers");
      if (headers != null)
        for (String key : Arrays.asList("User-Agent", "Referer", "Origin")) {
          String value = headers.optString(key);
          if (!value.isEmpty() && !value.contains("\r") && !value.contains("\n"))
            conn.setRequestProperty(key, value);
        }
      int status = conn.getResponseCode();
      report.append("\nStream manifest HTTP: ").append(status);
      if (status != 200) return;
      String type = String.valueOf(conn.getContentType()).toLowerCase(Locale.ROOT);
      // Read manifest text only, never a segment or media file.
      if (!type.contains("mpegurl")
          && !type.contains("text/")
          && !address.getPath().endsWith(".m3u8")) return;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = conn.getInputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > 262144)
            throw new IOException("Manifest exceeds diagnostic limit");
          out.write(buffer, 0, count);
        }
      }
      String manifest = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
      if (!manifest.startsWith("#EXTM3U")) return;
      URI finalAddress = conn.getURL().toURI();
      int references = 0;
      for (String line : manifest.split("\\R")) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          if (hosts.size() < 16) host(hosts, finalAddress.resolve(line).toString());
          references++;
        } else {
          java.util.regex.Matcher match =
              java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
          while (match.find() && hosts.size() < 16)
            host(hosts, finalAddress.resolve(match.group(1)).toString());
        }
      }
      report.append("\nManifest references: ").append(references);
    } catch (Exception e) {
      report.append("\nStream manifest: ").append(clean(Engine.friendly(e)));
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static void host(Set<String> hosts, String value) {
    try {
      String h = new URI(value).getHost();
      if (h != null && h.matches("[a-zA-Z0-9.-]{1,253}")) hosts.add(h.toLowerCase(Locale.ROOT));
    } catch (Exception ignored) {
    }
  }

  private static JSONArray nativeDns(Context c, Set<String> hosts) throws Exception {
    File base = new File(c.getNoBackupFilesDir(), "youtubedl-android/packages"),
        python = new File(base, "python/usr");
    String script =
        "import socket,sys,json,threading,importlib.util\n"
            + "spec=importlib.util.spec_from_file_location('ml_dns',sys.argv[2])\n"
            + "resolver=importlib.util.module_from_spec(spec);spec.loader.exec_module(resolver)\n"
            + "results=[]\n"
            + "def lookup(host):\n"
            + " row={'host':host}\n"
            + " for key,family in [('any',socket.AF_UNSPEC),('ipv4',socket.AF_INET)]:\n"
            + "  try:\n"
            + "   socket.getaddrinfo(host,443,family,socket.SOCK_STREAM);row[key]='resolved'\n"
            + "  except Exception as e: row[key]=str(e)\n"
            + " results.append(row)\n"
            + "threads=[threading.Thread(target=lookup,args=(h,),daemon=True) for h in"
            + " json.loads(sys.argv[1])]\n"
            + "for t in threads:t.start()\n"
            + "for t in threads:t.join(4)\n"
            + "print(json.dumps(results),flush=True)\n";
    ProcessBuilder builder =
        new ProcessBuilder(
            new File(c.getApplicationInfo().nativeLibraryDir, "libpython.so").getAbsolutePath(),
            "-c",
            script,
            new JSONArray(hosts).toString(),
            new File(
                    c.getNoBackupFilesDir(),
                    "network-plugins/medialens/yt_dlp_plugins/extractor/medialens_dns.py")
                .getAbsolutePath());
    Map<String, String> env = builder.environment();
    env.put(
        "LD_LIBRARY_PATH",
        new File(python, "lib").getAbsolutePath()
            + ":"
            + new File(base, "ffmpeg/usr/lib").getAbsolutePath()
            + ":"
            + new File(base, "aria2c/usr/lib").getAbsolutePath());
    env.put("SSL_CERT_FILE", new File(python, "etc/tls/cert.pem").getAbsolutePath());
    env.put("PYTHONHOME", python.getAbsolutePath());
    env.put("HOME", python.getAbsolutePath());
    env.put("TMPDIR", c.getCacheDir().getAbsolutePath());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    if (!process.waitFor(24, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("DNS check timed out");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[2048];
    int n;
    try (InputStream in = process.getInputStream()) {
      while ((n = in.read(buffer)) != -1) {
        if (bytes.size() + n > 16000) throw new IOException("DNS report exceeded its limit");
        bytes.write(buffer, 0, n);
      }
    }
    String output = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    if (process.exitValue() != 0)
      throw new IOException("Native DNS check could not start: " + clean(output));
    return new JSONArray(output);
  }

  private static String clean(String text) {
    String s =
        text.replaceAll("https?://[^\\s]+", "[address]")
            .replaceAll("(?m)^.*(Destination|filename).*$", "");
    return s.length() > 300 ? s.substring(0, 300) : s;
  }
}
