package io.github.graemevip.medialens;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout root, home, library, results, nav, linkForm, mediaTypes;
  private TextView hero, subtitle;
  private boolean preview;
  private ScrollView scroll;
  private EditText input;
  private TextView find, feedback, qualityLabel, downloadTab;
  private String resolvedUrl, errorDetails;
  private JSONObject media;
  private final ArrayList<String> qualityNames = new ArrayList<>(),
      qualityValues = new ArrayList<>();
  private int generation, qualityIndex;
  private boolean busy, showingLibrary;
  private Dialog openSheet;
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          renderLibrary();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (!DownloadService.running) Jobs.recover(this);
    root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int top, bottom;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            top = bars.top;
            bottom = bars.bottom;
          } else {
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
          }
          root.setPadding(0, top, 0, bottom);
          return insets;
        });
    setContentView(root);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);

    buildHeader();
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    FrameLayout pages = new FrameLayout(this);
    scroll.addView(pages, new ScrollView.LayoutParams(-1, -1));
    home = Ui.column(this);
    home.setPadding(dp(24), dp(18), dp(24), dp(28));
    pages.addView(home, new FrameLayout.LayoutParams(-1, -2));
    library = Ui.column(this);
    library.setPadding(dp(24), dp(18), dp(24), dp(28));
    pages.addView(library, new FrameLayout.LayoutParams(-1, -2));
    buildHome();
    buildNav();
    renderLibrary();
    showPage(false);
    if (state != null) {
      input.setText(state.getString("input", ""));
      showPage(state.getBoolean("library", false));
    } else accept(getIntent());
  }

  private void buildHeader() {
    LinearLayout header = Ui.row(this);
    header.setPadding(dp(24), dp(12), dp(16), dp(8));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.ic_launcher);
    header.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
    TextView name = Ui.text(this, "Media Lens", 22, true);
    name.setPadding(dp(10), 0, 0, 0);
    header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(Ui.iconButton(this, "settings", "Settings", this::settings));
    root.addView(header);
  }

  private void buildHome() {
    Ui.gap(home, 14);
    hero = Ui.text(this, "Find it.\nKeep it.", 40, true);
    hero.setLineSpacing(0, 1.06f);
    home.addView(hero);
    Ui.gap(home, 12);
    subtitle = Ui.text(this, "Your favourites, saved.", 17, false);
    subtitle.setTextColor(Ui.MUTED);
    home.addView(subtitle);
    Ui.gap(home, 30);
    LinearLayout box = surface(home, 22, 20);
    linkForm = box;
    TextView label = Ui.text(this, "Media link", 15, true);
    label.setTextColor(Ui.MUTED);
    box.addView(label);
    Ui.gap(box, 10);
    LinearLayout link = Ui.row(this);
    input = new EditText(this);
    input.setTextSize(17);
    input.setTextColor(Ui.TEXT);
    input.setHintTextColor(Ui.MUTED);
    input.setHint("Paste a link here");
    input.setSingleLine(true);
    input.setBackgroundColor(Color.TRANSPARENT);
    input.setPadding(0, dp(8), dp(8), dp(8));
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    link.addView(input, new LinearLayout.LayoutParams(0, dp(54), 1));
    link.addView(
        Ui.iconButton(
            this,
            "paste",
            "Paste link",
            () -> {
              ClipboardManager cm = getSystemService(ClipboardManager.class);
              if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                input.setText(s);
              } else toast("Copy a link first");
            }));
    box.addView(link);
    Ui.gap(box, 12);
    find = Ui.button(this, "Find media  →", true);
    find.setOnClickListener(v -> analyse());
    box.addView(find, new LinearLayout.LayoutParams(-1, dp(56)));
    feedback = Ui.text(this, "", 16, false);
    feedback.setTextColor(Ui.MUTED);
    feedback.setPadding(0, dp(14), 0, 0);
    feedback.setVisibility(View.GONE);
    feedback.setOnClickListener(
        v -> {
          if (errorDetails != null) details("Download details", errorDetails);
        });
    box.addView(feedback);
    results = Ui.column(this);
    home.addView(results);
    input.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            generation++;
            media = null;
            resolvedUrl = null;
            resetPreview();
            feedback.setVisibility(View.GONE);
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    Ui.gap(home, 24);
    LinearLayout types = Ui.row(this);
    mediaTypes = types;
    types.setGravity(Gravity.CENTER);
    feature(types, "video", "Video");
    feature(types, "audio", "Audio");
    feature(types, "image", "Images");
    home.addView(types);
  }

  private void feature(LinearLayout parent, String icon, String title) {
    LinearLayout item = Ui.row(this);
    item.setGravity(Gravity.CENTER);
    Ui.Icon image = new Ui.Icon(this, icon, Ui.MUTED);
    item.addView(image, new LinearLayout.LayoutParams(dp(19), dp(19)));
    TextView t = Ui.text(this, title, 15, false);
    t.setTextColor(Ui.MUTED);
    t.setPadding(dp(7), 0, 0, 0);
    item.addView(t);
    parent.addView(item, new LinearLayout.LayoutParams(0, dp(36), 1));
  }

  private void buildNav() {
    nav = Ui.row(this);
    nav.setPadding(dp(20), dp(10), dp(20), dp(12));
    LinearLayout left = Ui.row(this);
    left.setGravity(Gravity.CENTER);
    left.setPadding(dp(10), dp(14), dp(10), dp(14));
    left.addView(
        new Ui.Icon(this, "link", Ui.ACCENT), new LinearLayout.LayoutParams(dp(22), dp(22)));
    TextView l = Ui.text(this, "New download", 15, true);
    l.setTextColor(Ui.ACCENT);
    l.setPadding(dp(9), 0, 0, 0);
    left.addView(l);
    Ui.ripple(left, Ui.SURFACE, 18);
    left.setOnClickListener(
        v -> {
          resetPreview();
          showPage(false);
        });
    LinearLayout right = Ui.row(this);
    right.setGravity(Gravity.CENTER);
    right.setPadding(dp(10), dp(14), dp(10), dp(14));
    right.addView(
        new Ui.Icon(this, "folder", Ui.MUTED), new LinearLayout.LayoutParams(dp(22), dp(22)));
    downloadTab = Ui.text(this, "Library", 15, true);
    downloadTab.setPadding(dp(9), 0, 0, 0);
    right.addView(downloadTab);
    Ui.ripple(right, Color.TRANSPARENT, 18);
    right.setOnClickListener(v -> showPage(true));
    nav.addView(left, new LinearLayout.LayoutParams(0, dp(54), 1));
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(54), 1);
    rp.leftMargin = dp(10);
    nav.addView(right, rp);
    root.addView(nav);
  }

  private void showPage(boolean downloads) {
    showingLibrary = downloads;
    home.setVisibility(downloads ? View.GONE : View.VISIBLE);
    library.setVisibility(downloads ? View.VISIBLE : View.GONE);
    if (nav != null) {
      for (int i = 0; i < 2; i++) {
        LinearLayout tab = (LinearLayout) nav.getChildAt(i);
        Ui.ripple(tab, (i == 1) == downloads ? Ui.SURFACE : Color.TRANSPARENT, 18);
        TextView name = (TextView) tab.getChildAt(1);
        name.setTextColor((i == 1) == downloads ? Ui.ACCENT : Ui.MUTED);
        Ui.Icon icon = (Ui.Icon) tab.getChildAt(0);
        icon.color = (i == 1) == downloads ? Ui.ACCENT : Ui.MUTED;
        icon.invalidate();
      }
    }
    scroll.post(() -> scroll.smoothScrollTo(0, 0));
  }

  private void resetPreview() {
    preview = false;
    for (int i = 0; i < home.getChildCount(); i++)
      if (home.getChildAt(i) instanceof Space) home.getChildAt(i).setVisibility(View.VISIBLE);
    results.removeAllViews();
    hero.setVisibility(View.VISIBLE);
    subtitle.setVisibility(View.VISIBLE);
    linkForm.setVisibility(View.VISIBLE);
    if (mediaTypes != null) mediaTypes.setVisibility(View.VISIBLE);
    scroll.post(() -> scroll.smoothScrollTo(0, 0));
  }

  private void setBusy(boolean value) {
    busy = value;
    find.setEnabled(!value);
    find.setAlpha(value ? .6f : 1f);
    find.setText(value ? "Finding media…" : "Find media  →");
  }

  private void analyse() {
    if (busy) return;
    final String url;
    try {
      url = Engine.url(input.getText().toString());
    } catch (Exception e) {
      feedback.setText("Paste a video or image link to get started.");
      feedback.setVisibility(View.VISIBLE);
      return;
    }
    final int expected = generation;
    setBusy(true);
    feedback.setVisibility(View.GONE);
    results.removeAllViews();
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
    input.clearFocus();
    worker.execute(
        () -> {
          try {
            JSONObject info = Engine.info(this, url);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  setBusy(false);
                  if (expected != generation) return;
                  media = info;
                  resolvedUrl = url;
                  showMedia();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  setBusy(false);
                  if (expected != generation) return;
                  errorDetails = Engine.friendly(e);
                  feedback.setText(userError(errorDetails) + " Tap for details.");
                  feedback.setVisibility(View.VISIBLE);
                });
          }
        });
  }

  private String userError(String error) {
    String s = error.toLowerCase(Locale.ROOT);
    if (s.contains("sign in")
        || s.contains("login")
        || s.contains("cookies")
        || s.contains("confirm you")) return "This site is asking you to sign in.";
    if (s.contains("unsupported url")) return "No downloadable media found at this link.";
    if (s.contains("16 kb")) return "This device needs a newer download engine.";
    if (s.contains("network") || s.contains("resolve") || s.contains("timed out"))
      return "Couldn’t connect. Try again in a moment.";
    return "Couldn’t find the media. Try updating the engine in Settings.";
  }

  private void showMedia() {
    results.removeAllViews();
    preview = true;
    for (int i = 0; i < home.getChildCount(); i++)
      if (home.getChildAt(i) instanceof Space) home.getChildAt(i).setVisibility(View.GONE);
    hero.setVisibility(View.GONE);
    subtitle.setVisibility(View.GONE);
    linkForm.setVisibility(View.GONE);
    mediaTypes.setVisibility(View.GONE);
    TextView change = Ui.text(this, "←  Change link", 16, true);
    change.setTextColor(Ui.MUTED);
    change.setPadding(0, dp(4), 0, dp(14));
    change.setMinHeight(dp(48));
    change.setOnClickListener(v -> resetPreview());
    results.addView(change);
    qualityIndex = 0;
    qualityNames.clear();
    qualityValues.clear();
    qualityNames.add(media.optBoolean("image") ? "Original image" : "Best available");
    qualityValues.add("best");
    if (!media.optBoolean("image")) {
      TreeSet<Integer> heights = new TreeSet<>(Collections.reverseOrder());
      JSONArray formats = media.optJSONArray("formats");
      if (formats != null)
        for (int i = 0; i < formats.length(); i++) {
          JSONObject o = formats.optJSONObject(i);
          if (o != null && o.optInt("height") > 0 && !"none".equals(o.optString("vcodec")))
            heights.add(o.optInt("height"));
        }
      for (int height : heights) {
        qualityNames.add("Up to " + height + "p");
        qualityValues.add(Integer.toString(height));
      }
      qualityNames.add("Audio only · M4A");
      qualityValues.add("audio");
    }
    Ui.gap(results, 20);
    LinearLayout card = surface(results, 24, 18);
    FrameLayout artwork = new FrameLayout(this);
    artwork.setBackground(Ui.shape(this, Color.rgb(28, 44, 55), 16, false));
    artwork.setClipToOutline(true);
    int width = getResources().getDisplayMetrics().widthPixels - dp(84);
    card.addView(artwork, new LinearLayout.LayoutParams(-1, Math.max(dp(150), width * 9 / 16)));
    ImageView photo = new ImageView(this);
    photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
    artwork.addView(photo, new FrameLayout.LayoutParams(-1, -1));
    Ui.Icon symbol = new Ui.Icon(this, media.optBoolean("image") ? "image" : "video", Ui.ACCENT);
    artwork.addView(symbol, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
    double seconds = media.optDouble("duration", 0);
    if (seconds > 0) {
      TextView duration =
          Ui.text(
              this,
              String.format(Locale.ROOT, "%d:%02d", (int) seconds / 60, (int) seconds % 60),
              15,
              true);
      duration.setPadding(dp(9), dp(5), dp(9), dp(5));
      duration.setBackground(Ui.shape(this, Color.argb(220, 11, 17, 23), 8, false));
      FrameLayout.LayoutParams lp =
          new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
      lp.setMargins(0, 0, dp(10), dp(10));
      artwork.addView(duration, lp);
    }
    thumbnail(media.optString("thumbnail"), photo, symbol, generation);
    Ui.gap(card, 18);
    TextView title = Ui.text(this, media.optString("title", "Your media"), 20, true);
    title.setMaxLines(3);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(title);
    String creator = media.optString("uploader", media.optString("extractor_key", ""));
    if (!creator.isEmpty() && !"Generic".equalsIgnoreCase(creator)) {
      Ui.gap(card, 7);
      TextView by = Ui.text(this, creator, 15, false);
      by.setTextColor(Ui.MUTED);
      card.addView(by);
    }
    if (!media.optBoolean("image")) {
      Ui.gap(card, 18);
      LinearLayout quality = Ui.row(this);
      quality.setPadding(dp(14), dp(14), dp(14), dp(14));
      Ui.ripple(quality, Ui.BG, 12);
      qualityLabel = Ui.text(this, qualityNames.get(0), 16, true);
      quality.addView(qualityLabel, new LinearLayout.LayoutParams(0, -2, 1));
      quality.addView(
          new Ui.Icon(this, "chevron", Ui.MUTED), new LinearLayout.LayoutParams(dp(20), dp(20)));
      quality.setOnClickListener(v -> qualities());
      card.addView(quality, new LinearLayout.LayoutParams(-1, dp(52)));
    }
    Ui.gap(card, 14);
    TextView download = Ui.button(this, "Download  ↓", true);
    download.setOnClickListener(v -> download());
    card.addView(download, new LinearLayout.LayoutParams(-1, dp(56)));
    results.setAlpha(0f);
    results.animate().alpha(1f).setDuration(160).start();
    scroll.post(() -> scroll.smoothScrollTo(0, 0));
  }

  private void thumbnail(String url, ImageView target, View placeholder, int expected) {
    if (url.isEmpty() || !url.startsWith("https://")) return;
    worker.execute(
        () -> {
          HttpURLConnection connection = null;
          try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
              byte[] b = new byte[8192];
              int n;
              while ((n = in.read(b)) != -1) {
                if (bytes.size() + n > 4_000_000) return;
                bytes.write(b, 0, n);
              }
            }
            byte[] raw = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 1200);
            Bitmap image = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            if (image == null) return;
            runOnUiThread(
                () -> {
                  if (!isDestroyed() && expected == generation) {
                    target.setImageBitmap(image);
                    placeholder.setVisibility(View.GONE);
                  }
                });
          } catch (Exception ignored) {
          } finally {
            if (connection != null) connection.disconnect();
          }
        });
  }

  private void qualities() {
    LinearLayout body = sheet("Download quality");
    for (int i = 0; i < qualityNames.size(); i++) {
      final int index = i;
      LinearLayout row = Ui.row(this);
      row.setPadding(dp(12), dp(16), dp(12), dp(16));
      Ui.ripple(row, i == qualityIndex ? Ui.BORDER : Color.TRANSPARENT, 12);
      TextView name = Ui.text(this, qualityNames.get(i), 17, i == qualityIndex);
      row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      if (i == qualityIndex)
        row.addView(
            new Ui.Icon(this, "check", Ui.ACCENT), new LinearLayout.LayoutParams(dp(23), dp(23)));
      row.setOnClickListener(
          v -> {
            qualityIndex = index;
            qualityLabel.setText(qualityNames.get(index));
            closeSheet();
          });
      body.addView(row);
    }
  }

  private void download() {
    if (media == null || resolvedUrl == null) return;
    JSONObject job = new JSONObject();
    Jobs.put(job, "id", UUID.randomUUID().toString());
    Jobs.put(job, "url", resolvedUrl);
    Jobs.put(job, "title", media.optString("title", "Media"));
    Jobs.put(job, "quality", qualityValues.get(qualityIndex));
    Jobs.put(job, "image", media.optBoolean("image"));
    Jobs.put(job, "created", System.currentTimeMillis());
    enqueue(job);
  }

  private void enqueue(JSONObject job) {
    JSONArray all = Jobs.all(this);
    int count = 0;
    for (int i = 0; i < all.length(); i++) {
      String state = all.optJSONObject(i).optString("status");
      if ("queued".equals(state) || "downloading".equals(state)) count++;
    }
    if (count >= 10) {
      toast("Your queue is full. Let a download finish first.");
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 12);
    Jobs.put(job, "status", "queued");
    Jobs.put(job, "progress", 0);
    Jobs.put(job, "error", "");
    Jobs.save(this, job);
    startForegroundService(
        new Intent(this, DownloadService.class).putExtra("id", job.optString("id")));
    renderLibrary();
    showPage(true);
  }

  private void renderLibrary() {
    if (library == null) return;
    library.removeAllViews();
    JSONArray jobs = Jobs.all(this);
    if (downloadTab != null)
      downloadTab.setText(jobs.length() > 0 ? "Library · " + jobs.length() : "Library");
    LinearLayout heading = Ui.row(this);
    heading.addView(Ui.text(this, "Library", 32, true), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(
        Ui.iconButton(
            this,
            "folder",
            "Open Downloads",
            () -> {
              try {
                startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
              } catch (ActivityNotFoundException e) {
                toast("Open Files → Downloads → Media Lens");
              }
            }));
    library.addView(heading);
    Ui.gap(library, 8);
    if (jobs.length() == 0) {
      Ui.gap(library, 65);
      FrameLayout circle = new FrameLayout(this);
      circle.setBackground(Ui.shape(this, Ui.SURFACE, 100, false));
      Ui.Icon icon = new Ui.Icon(this, "download", Ui.ACCENT);
      circle.addView(icon, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(92), dp(92));
      cp.gravity = Gravity.CENTER;
      library.addView(circle, cp);
      Ui.gap(library, 24);
      TextView empty = Ui.text(this, "Nothing saved yet", 23, true);
      empty.setGravity(Gravity.CENTER);
      library.addView(empty);
      Ui.gap(library, 10);
      TextView sub = Ui.text(this, "Add a link to get started.", 17, false);
      sub.setTextColor(Ui.MUTED);
      sub.setGravity(Gravity.CENTER);
      library.addView(sub);
      Ui.gap(library, 25);
      TextView add = Ui.button(this, "Add a link", false);
      add.setOnClickListener(v -> showPage(false));
      library.addView(add);
      return;
    }
    for (int i = 0; i < jobs.length(); i++) {
      JSONObject job = jobs.optJSONObject(i);
      if (job == null) continue;
      Ui.gap(library, 14);
      LinearLayout card = surface(library, 20, 16);
      LinearLayout row = Ui.row(this);
      FrameLayout tile = new FrameLayout(this);
      tile.setBackground(Ui.shape(this, Ui.BORDER, 14, false));
      Ui.Icon icon =
          new Ui.Icon(
              this,
              job.optBoolean("image")
                  ? "image"
                  : ("audio".equals(job.optString("quality")) ? "audio" : "video"),
              Ui.ACCENT);
      tile.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
      row.addView(tile, new LinearLayout.LayoutParams(dp(48), dp(48)));
      LinearLayout description = Ui.column(this);
      description.setPadding(dp(13), 0, dp(4), 0);
      TextView title = Ui.text(this, job.optString("title", "Media"), 17, true);
      title.setMaxLines(2);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      description.addView(title);
      Ui.gap(description, 6);
      String state = job.optString("status"), label;
      switch (state) {
        case "complete":
          label = "Saved";
          break;
        case "downloading":
          label =
              job.optInt("progress") > 0
                  ? "Downloading · " + job.optInt("progress") + "%"
                  : "Starting…";
          break;
        case "queued":
          label = "In queue";
          break;
        case "cancelled":
          label = "Cancelled";
          break;
        case "interrupted":
          label = "Interrupted";
          break;
        default:
          label = "Couldn’t download";
      }
      TextView status = Ui.text(this, label, 15, false);
      status.setTextColor("complete".equals(state) ? Ui.ACCENT : Ui.MUTED);
      description.addView(status);
      row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
      if ("queued".equals(state) || "downloading".equals(state))
        row.addView(
            Ui.iconButton(
                this,
                "close",
                "Cancel download",
                () ->
                    startService(
                        new Intent(this, DownloadService.class)
                            .setAction("cancel")
                            .putExtra("id", job.optString("id")))));
      else if ("complete".equals(state))
        row.addView(Ui.iconButton(this, "external", "Open file", () -> openFile(job)));
      else row.addView(Ui.iconButton(this, "retry", "Retry download", () -> enqueue(job)));
      card.addView(row);
      if ("downloading".equals(state)) {
        Ui.gap(card, 14);
        ProgressBar progress =
            new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(
            android.content.res.ColorStateList.valueOf(Ui.BORDER));
        progress.setProgress(job.optInt("progress"));
        progress.setIndeterminate(job.optInt("progress") == 0);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
      }
      if ("failed".equals(state) || "interrupted".equals(state)) {
        Ui.gap(card, 9);
        TextView more = Ui.text(this, "View details", 15, false);
        more.setTextColor(Ui.MUTED);
        more.setPadding(0, dp(8), 0, dp(8));
        more.setOnClickListener(
            v ->
                details(
                    "Download details", job.optString("error", "Please try this download again.")));
        card.addView(more);
      }
    }
  }

  private void openFile(JSONObject job) {
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(Uri.parse(job.optString("uri")), job.optString("mime", "*/*"))
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (ActivityNotFoundException e) {
      toast("No viewer installed. Your file is in Downloads / Media Lens.");
    }
  }

  private LinearLayout sheet(String title) {
    closeSheet();
    Dialog dialog = new Dialog(this);
    openSheet = dialog;
    LinearLayout outer = Ui.column(this);
    outer.setPadding(dp(24), dp(16), dp(24), dp(30));
    outer.setBackground(Ui.shape(this, Ui.SURFACE, 28, false));
    LinearLayout heading = Ui.row(this);
    heading.addView(Ui.text(this, title, 24, true), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(Ui.iconButton(this, "close", "Close", this::closeSheet));
    outer.addView(heading);
    Ui.gap(outer, 12);
    ScrollView container = new ScrollView(this);
    container.setVerticalScrollBarEnabled(false);
    LinearLayout body = Ui.column(this);
    container.addView(body);
    outer.addView(container, new LinearLayout.LayoutParams(-1, -2));
    dialog.setContentView(outer);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setDimAmount(.65f);
      w.setGravity(Gravity.BOTTOM);
      w.setWindowAnimations(android.R.style.Animation_InputMethod);
    }
    dialog.show();
    if (w != null) {
      w.setLayout(-1, -2);
      int max = (int) (getResources().getDisplayMetrics().heightPixels * .72);
      outer.post(
          () -> {
            if (outer.getHeight() > max) w.setLayout(-1, max);
          });
    }
    return body;
  }

  private void settings() {
    LinearLayout body = sheet("Settings");
    settingRow(
        body,
        "retry",
        "Update engine",
        "Refresh site support",
        () -> {
          closeSheet();
          updateEngine();
        });
    settingRow(body, "globe", "Supported sites", "YouTube, X, adult sites & more", this::sites);
    settingRow(
        body,
        "external",
        "Help & source",
        "GitHub / MediaLens",
        () -> openUrl("https://github.com/GraemeVIP/MediaLens"));
    settingRow(
        body,
        "trash",
        "Clear history",
        "Saved files stay on your phone",
        () -> {
          Jobs.clearFinished(this);
          renderLibrary();
          closeSheet();
          toast("History cleared");
        });
    Ui.gap(body, 20);
    TextView version = Ui.text(this, "Media Lens 0.1.0 · GPL-3.0-or-later", 15, false);
    version.setTextColor(Ui.MUTED);
    body.addView(version);
  }

  private void settingRow(
      LinearLayout body, String icon, String title, String subtitle, Runnable action) {
    LinearLayout row = Ui.row(this);
    row.setPadding(dp(4), dp(15), dp(4), dp(15));
    Ui.ripple(row, Color.TRANSPARENT, 14);
    row.addView(new Ui.Icon(this, icon, Ui.MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
    LinearLayout words = Ui.column(this);
    words.setPadding(dp(16), 0, dp(8), 0);
    words.addView(Ui.text(this, title, 17, true));
    Ui.gap(words, 6);
    TextView sub = Ui.text(this, subtitle, 15, false);
    sub.setTextColor(Ui.MUTED);
    words.addView(sub);
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    row.setOnClickListener(v -> action.run());
    body.addView(row);
  }

  private void sites() {
    LinearLayout body = sheet("Made for your links");
    TextView names =
        Ui.text(
            this,
            "YouTube\nX / Twitter\nVimeo\nPornhub\nxHamster\nXVideos\nXNXX\nRedGifs",
            20,
            true);
    names.setLineSpacing(dp(13), 1);
    body.addView(names);
    Ui.gap(body, 22);
    TextView more = Ui.text(this, "And many more, powered by yt-dlp.", 16, false);
    more.setTextColor(Ui.MUTED);
    body.addView(more);
  }

  private void updateEngine() {
    if (DownloadService.running) {
      toast("Let your downloads finish first");
      return;
    }
    if (busy) {
      toast("Finding your media. One moment…");
      return;
    }
    busy = true;
    toast("Updating download engine…");
    worker.execute(
        () -> {
          try {
            Engine.init(this);
            YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
            runOnUiThread(
                () -> {
                  if (!isDestroyed()) {
                    busy = false;
                    toast("Download engine is up to date");
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (!isDestroyed()) {
                    busy = false;
                    details("Couldn’t update", Engine.friendly(e));
                  }
                });
          }
        });
  }

  private void details(String title, String text) {
    LinearLayout body = sheet(title);
    TextView t = Ui.text(this, text, 16, false);
    t.setTextColor(Ui.MUTED);
    t.setTextIsSelectable(true);
    body.addView(t);
  }

  private void closeSheet() {
    if (openSheet != null) {
      openSheet.dismiss();
      openSheet = null;
    }
  }

  private void openUrl(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      toast("No browser installed");
    }
  }

  private LinearLayout surface(LinearLayout parent, int radius, int padding) {
    LinearLayout card = Ui.column(this);
    card.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
    card.setBackground(Ui.shape(this, Ui.SURFACE, radius, true));
    parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    return card;
  }

  private int dp(int size) {
    return Ui.dp(this, size);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
  }

  private void back() {
    if (openSheet != null) {
      closeSheet();
      return;
    }
    if (showingLibrary) {
      showPage(false);
      return;
    }
    if (preview) {
      resetPreview();
      return;
    }
    finish();
  }

  // API 33+ uses the registered OnBackInvokedCallback; this override supports Android 10–12.
  @android.annotation.SuppressLint("GestureBackNavigation")
  @Override
  public void onBackPressed() {
    if (Build.VERSION.SDK_INT < 33) back();
    else super.onBackPressed();
  }

  private void accept(Intent intent) {
    if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
    CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    if (value == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
      value = intent.getClipData().getItemAt(0).getText();
    if (value != null) {
      input.setText(value.toString());
      input.clearFocus();
      showPage(false);
    }
  }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    input.post(() -> accept(i));
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("input", input.getText().toString());
    b.putBoolean("library", showingLibrary);
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          receiver, new IntentFilter(getPackageName() + ".JOBS"), Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(receiver, new IntentFilter(getPackageName() + ".JOBS"));
    renderLibrary();
  }

  @Override
  protected void onStop() {
    unregisterReceiver(receiver);
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    closeSheet();
    worker.shutdown();
    super.onDestroy();
  }
}
