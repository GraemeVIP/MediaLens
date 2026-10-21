package io.github.graemevip.medialens;

import java.util.*;

/** Structured byte counts from yt-dlp, formatted in decimal units for the app. */
final class Transfer {
  static final String PREFIX = "ML_PROGRESS\t";
  static final String TEMPLATE =
      "download:"
          + PREFIX
          + "%(progress.status)s\t%(progress.downloaded_bytes)s\t%(progress.total_bytes)s"
          + "\t%(progress.total_bytes_estimate)s\t%(progress.speed)s\t%(progress.eta)s"
          + "\t%(info.format_id)s";
  private final Map<String, long[]> parts = new HashMap<>();
  long downloaded, total;
  double bytesPerSecond;
  long eta = -1;
  boolean estimated;

  boolean read(String line) {
    if (!line.startsWith(PREFIX)) return false;
    String[] values = line.substring(PREFIX.length()).split("\t", -1);
    if (values.length != 7) return false;
    long bytes = number(values[1]), exact = number(values[2]), estimate = number(values[3]);
    parts.put(
        values[6],
        new long[] {
          bytes,
          exact > 0 ? Math.max(bytes, exact) : estimate > 0 ? Math.max(bytes, estimate) : 0,
          exact > 0 ? 0 : 1
        });
    downloaded = total = 0;
    estimated = false;
    for (long[] part : parts.values()) {
      downloaded += part[0];
      total += part[1];
      estimated |= part[2] == 1;
    }
    for (long[] part : parts.values()) if (part[1] == 0) total = 0;
    bytesPerSecond = decimal(values[4]);
    eta = "NA".equals(values[5]) || "None".equals(values[5]) ? -1 : number(values[5]);
    return true;
  }

  int percent() {
    return total > 0 ? (int) Math.min(99, downloaded * 100d / total) : 0;
  }

  String summary() {
    String text = size(downloaded);
    if (total > 0 && (total > downloaded || !estimated))
      text += " / " + (estimated ? "≈" : "") + size(total);
    else text += " downloaded";
    if (bytesPerSecond > 0) text += "\n" + speed(bytesPerSecond);
    if (eta > 0)
      text += " · " + (eta >= 60 ? eta / 60 + "m " + eta % 60 + "s" : eta + "s") + " left";
    return text;
  }

  static String size(long bytes) {
    if (bytes >= 1000000000) return String.format(Locale.UK, "%.2f GB", bytes / 1e9);
    if (bytes >= 1000000) return String.format(Locale.UK, "%.1f MB", bytes / 1e6);
    return String.format(Locale.UK, "%.0f KB", Math.max(0, bytes) / 1000d);
  }

  static String speed(double bytesPerSecond) {
    return String.format(Locale.UK, "%.1f Mbps", Math.max(0, bytesPerSecond) * 8 / 1e6);
  }

  private static long number(String value) {
    return (long) Math.min(1e15, decimal(value));
  }

  private static double decimal(String value) {
    try {
      double n = Double.parseDouble(value);
      return Double.isFinite(n) && n >= 0 ? n : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
