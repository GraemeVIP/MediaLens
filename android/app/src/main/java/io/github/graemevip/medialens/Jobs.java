package io.github.graemevip.medialens;

import android.content.Context;
import org.json.*;

final class Jobs {
  static synchronized JSONArray all(Context c) {
    try {
      return new JSONArray(c.getSharedPreferences("downloads", 0).getString("jobs", "[]"));
    } catch (JSONException e) {
      return new JSONArray();
    }
  }

  static synchronized JSONObject get(Context c, String id) {
    JSONArray a = all(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject j = a.optJSONObject(i);
      if (j != null && id.equals(j.optString("id"))) return j;
    }
    return null;
  }

  static synchronized void save(Context c, JSONObject job) {
    JSONArray a = all(c), out = new JSONArray();
    out.put(job);
    for (int i = 0; i < a.length() && out.length() < 50; i++) {
      JSONObject j = a.optJSONObject(i);
      if (j != null && !job.optString("id").equals(j.optString("id"))) out.put(j);
    }
    c.getSharedPreferences("downloads", 0).edit().putString("jobs", out.toString()).apply();
    c.sendBroadcast(
        new android.content.Intent(c.getPackageName() + ".JOBS").setPackage(c.getPackageName()));
  }

  static synchronized void recover(Context c) {
    JSONArray a = all(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject j = a.optJSONObject(i);
      if (j != null
          && ("downloading".equals(j.optString("status"))
              || "queued".equals(j.optString("status")))) {
        put(j, "status", "interrupted");
        put(j, "error", "Download interrupted. Tap Retry to start again.");
        save(c, j);
      }
    }
  }

  static synchronized void clearFinished(Context c) {
    JSONArray out = new JSONArray(), all = all(c);
    for (int i = 0; i < all.length(); i++) {
      JSONObject j = all.optJSONObject(i);
      if (j != null
          && ("queued".equals(j.optString("status"))
              || "downloading".equals(j.optString("status")))) out.put(j);
    }
    c.getSharedPreferences("downloads", 0).edit().putString("jobs", out.toString()).apply();
  }

  static void put(JSONObject o, String k, Object v) {
    try {
      o.put(k, v);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
