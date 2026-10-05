package com.seekgodtakeall.karaokestudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 4401;
    private static final String PREFS = "karaoke_studio_v2";
    private static final String KEY_API = "api_base";
    private static final int BG = Color.rgb(8, 11, 20);
    private static final int CARD = Color.rgb(20, 25, 40);
    private static final int CARD_ALT = Color.rgb(27, 33, 51);
    private static final int TEXT = Color.rgb(246, 247, 251);
    private static final int MUTED = Color.rgb(166, 175, 194);
    private static final int ACCENT = Color.rgb(139, 124, 255);
    private static final int GREEN = Color.rgb(83, 211, 158);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout root;
    private Uri pendingAudio;
    private String pendingAction;
    private String activeJobId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showHome();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setLineSpacing(0, 1.08f);
        return t;
    }

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable outlined(int fill, int stroke, int radiusDp) {
        GradientDrawable g = rounded(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout page() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(30));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        root = content;
        return content;
    }

    private void addTopBar(LinearLayout parent, boolean back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(18));

        if (back) {
            Button b = new Button(this);
            b.setText("‹");
            b.setTextSize(28);
            b.setTextColor(TEXT);
            b.setBackground(rounded(CARD_ALT, 14));
            b.setContentDescription("Back");
            b.setMinWidth(dp(48));
            b.setMinHeight(dp(48));
            b.setOnClickListener(v -> showHome());
            bar.addView(b, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = back ? dp(14) : 0;
        TextView name = text("Karaoke Studio AI", 21, TEXT, true);
        TextView sub = text("Create. Separate. Master.", 13, MUTED, false);
        titleBox.addView(name);
        titleBox.addView(sub);
        bar.addView(titleBox, titleParams);

        Button settings = new Button(this);
        settings.setText("⚙");
        settings.setTextSize(18);
        settings.setTextColor(TEXT);
        settings.setBackground(rounded(CARD_ALT, 14));
        settings.setContentDescription("Settings");
        settings.setMinWidth(dp(48));
        settings.setMinHeight(dp(48));
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));

        parent.addView(bar);
    }

    private void showHome() {
        LinearLayout p = page();
        addTopBar(p, false);

        TextView hero = text("Your mobile audio studio", 30, TEXT, true);
        hero.setPadding(0, dp(10), 0, dp(8));
        p.addView(hero);

        TextView desc = text("Turn authorized video links or your own audio into karaoke tracks, clean MP3s and mastered mixes — without a desktop.", 16, MUTED, false);
        desc.setPadding(0, 0, 0, dp(24));
        p.addView(desc);

        addActionCard(p, "▶", "YouTube → Karaoke", "Remove lead vocals and keep music + backing vocals.", "yt_karaoke");
        addActionCard(p, "♫", "YouTube → MP3", "Create a high-quality MP3 from content you own or are authorized to process.", "yt_mp3");
        addActionCard(p, "🎤", "Upload → Karaoke", "Choose an audio file from your phone and create a karaoke version.", "upload_karaoke");
        addActionCard(p, "✦", "Master Audio", "Enhance loudness, clarity and polish with mastering presets.", "master");

        TextView recent = text("Recent projects", 18, TEXT, true);
        recent.setPadding(0, dp(26), 0, dp(10));
        p.addView(recent);

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setPadding(dp(16), dp(16), dp(16), dp(16));
        empty.setBackground(rounded(CARD, 18));
        TextView e1 = text("No projects yet", 15, TEXT, true);
        TextView e2 = text("Your completed tracks will appear here in a future update.", 13, MUTED, false);
        e2.setPadding(0, dp(4), 0, 0);
        empty.addView(e1);
        empty.addView(e2);
        p.addView(empty);

        addServiceStatus(p);
    }

    private void addActionCard(LinearLayout parent, String icon, String title, String subtitle, String action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(CARD, 20));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(title + ". " + subtitle);
        card.setMinimumHeight(dp(88));

        TextView i = text(icon, 25, TEXT, false);
        i.setGravity(Gravity.CENTER);
        i.setBackground(rounded(CARD_ALT, 16));
        card.addView(i, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(14);
        TextView t = text(title, 17, TEXT, true);
        TextView s = text(subtitle, 13, MUTED, false);
        s.setPadding(0, dp(4), 0, 0);
        copy.addView(t);
        copy.addView(s);
        card.addView(copy, cp);

        TextView arrow = text("›", 28, MUTED, false);
        card.addView(arrow);

        card.setOnClickListener(v -> {
            if (!isConfigured()) {
                showConfigureFirstDialog();
                return;
            }
            if ("yt_karaoke".equals(action)) showYoutubeScreen(true);
            else if ("yt_mp3".equals(action)) showYoutubeScreen(false);
            else if ("upload_karaoke".equals(action)) chooseAudio("upload_karaoke");
            else if ("master".equals(action)) chooseAudio("master");
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(card, lp);
    }

    private void addServiceStatus(LinearLayout parent) {
        TextView label = text("Cloud service", 14, MUTED, true);
        label.setPadding(0, dp(24), 0, dp(8));
        parent.addView(label);

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(rounded(CARD, 16));

        TextView dot = text("●", 16, isConfigured() ? GREEN : Color.rgb(232, 164, 76), false);
        status.addView(dot);
        TextView copy = text(isConfigured() ? "Configured" : "Setup required", 14, TEXT, true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(10);
        status.addView(copy, cp);

        Button test = new Button(this);
        test.setText(isConfigured() ? "Test" : "Setup");
        test.setTextColor(TEXT);
        test.setTextSize(13);
        test.setAllCaps(false);
        test.setBackground(rounded(CARD_ALT, 14));
        test.setMinHeight(dp(44));
        test.setOnClickListener(v -> {
            if (isConfigured()) testService();
            else showSettings();
        });
        status.addView(test);
        parent.addView(status);
    }

    private void showConfigureFirstDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Cloud service setup")
                .setMessage("This standalone mobile version uses its own cloud processing service, not your desktop. Add the API address once in Settings, then the app works independently.")
                .setPositiveButton("Open Settings", (d, w) -> showSettings())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean isConfigured() {
        String api = prefs.getString(KEY_API, "");
        return api != null && api.trim().startsWith("http");
    }

    private String apiBase() {
        String s = prefs.getString(KEY_API, "");
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void showSettings() {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text("Settings", 28, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(6));
        p.addView(h);

        TextView d = text("Connect this app to the independent Karaoke Studio cloud backend. Your PC is not used.", 15, MUTED, false);
        d.setPadding(0, 0, 0, dp(20));
        p.addView(d);

        TextView lab = text("Cloud API address", 14, TEXT, true);
        lab.setPadding(0, 0, 0, dp(8));
        p.addView(lab);

        EditText input = new EditText(this);
        input.setText(apiBase());
        input.setHint("https://your-karaoke-api.example.com");
        input.setHintTextColor(Color.rgb(108, 117, 136));
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setBackground(outlined(CARD, Color.rgb(58, 67, 88), 14));
        p.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        Button save = primaryButton("Save & test connection");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        sp.topMargin = dp(16);
        p.addView(save, sp);
        save.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                input.setError("Enter a full http:// or https:// address");
                return;
            }
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            prefs.edit().putString(KEY_API, value).apply();
            testService();
        });

        TextView note = text("For production, this address will be built into the APK so normal users will never see this setting.", 13, MUTED, false);
        note.setPadding(0, dp(18), 0, 0);
        p.addView(note);
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackground(rounded(ACCENT, 16));
        b.setMinHeight(dp(52));
        return b;
    }

    private void testService() {
        if (!isConfigured()) return;
        Toast.makeText(this, "Testing cloud service…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                JSONObject result = getJson(apiBase() + "/health");
                boolean ok = "ok".equalsIgnoreCase(result.optString("status"));
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(ok ? "Connected" : "Service responded")
                        .setMessage(ok ? "Karaoke Studio cloud processing is ready." : result.toString())
                        .setPositiveButton("OK", (d, w) -> showHome())
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Connection failed")
                        .setMessage("Could not reach the cloud service.\n\n" + friendlyError(e))
                        .setPositiveButton("OK", null)
                        .show());
            }
        });
    }

    private void showYoutubeScreen(boolean karaoke) {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text(karaoke ? "YouTube → Karaoke" : "YouTube → MP3", 27, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(6));
        p.addView(h);

        TextView d = text(karaoke
                ? "Paste a link to content you own or are authorized to process. We'll extract the audio and create a karaoke track."
                : "Paste a link to content you own or are authorized to process and create a high-quality MP3.",
                15, MUTED, false);
        d.setPadding(0, 0, 0, dp(20));
        p.addView(d);

        TextView lab = text("Authorized YouTube link", 14, TEXT, true);
        lab.setPadding(0, 0, 0, dp(8));
        p.addView(lab);

        EditText url = new EditText(this);
        url.setHint("https://youtube.com/watch?v=…");
        url.setHintTextColor(Color.rgb(108, 117, 136));
        url.setTextColor(TEXT);
        url.setTextSize(16);
        url.setSingleLine(true);
        url.setPadding(dp(14), dp(14), dp(14), dp(14));
        url.setBackground(outlined(CARD, Color.rgb(58, 67, 88), 14));
        p.addView(url, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        if (karaoke) {
            addInfoChip(p, "Backing vocals preserved when the source separation model can isolate them.");
        } else {
            addInfoChip(p, "MP3 output uses the highest-quality available authorized source audio.");
        }

        Button go = primaryButton(karaoke ? "Create karaoke track" : "Create MP3");
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        gp.topMargin = dp(18);
        p.addView(go, gp);
        go.setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (!value.startsWith("http")) {
                url.setError("Paste a valid YouTube URL");
                return;
            }
            startYoutubeJob(value, karaoke);
        });
    }

    private void addInfoChip(LinearLayout parent, String copy) {
        TextView chip = text(copy, 13, MUTED, false);
        chip.setPadding(dp(14), dp(12), dp(14), dp(12));
        chip.setBackground(rounded(CARD, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(chip, lp);
    }

    private void chooseAudio(String action) {
        pendingAction = action;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            pendingAudio = data.getData();
            try { getContentResolver().takePersistableUriPermission(pendingAudio, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            if ("master".equals(pendingAction)) showMasterOptions();
            else showUploadKaraokeOptions();
        }
    }

    private String fileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return "audio";
    }

    private void showUploadKaraokeOptions() {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text("Create karaoke", 27, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(4));
        p.addView(h);
        TextView f = text(fileName(pendingAudio), 14, MUTED, false);
        f.setPadding(0, 0, 0, dp(20));
        p.addView(f);

        addInfoChip(p, "Lead vocal removal · music retained · backing vocals preserved when possible");

        Button go = primaryButton("Create karaoke track");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(18);
        p.addView(go, lp);
        go.setOnClickListener(v -> startUploadJob("/jobs/upload-karaoke", "Creating karaoke"));
    }

    private void showMasterOptions() {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text("Master Audio", 27, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(4));
        p.addView(h);
        TextView f = text(fileName(pendingAudio), 14, MUTED, false);
        f.setPadding(0, 0, 0, dp(18));
        p.addView(f);

        TextView presets = text("Mastering preset", 15, TEXT, true);
        presets.setPadding(0, 0, 0, dp(10));
        p.addView(presets);

        final String[] selected = {"streaming"};
        addPreset(p, "Streaming", "Balanced clarity and loudness for online playback.", "streaming", selected);
        addPreset(p, "Warm", "Smoother highs and fuller low-mids.", "warm", selected);
        addPreset(p, "Punchy", "More impact and presence for energetic music.", "punchy", selected);
        addPreset(p, "Clean", "Gentle correction and transparent loudness control.", "clean", selected);

        Button go = primaryButton("Master audio");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(18);
        p.addView(go, lp);
        go.setOnClickListener(v -> startUploadJob("/jobs/master?preset=" + selected[0], "Mastering audio"));
    }

    private void addPreset(LinearLayout parent, String title, String subtitle, String value, String[] selected) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setText(title + "\n" + subtitle);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(outlined(CARD, "streaming".equals(value) ? ACCENT : Color.rgb(58,67,88), 14));
        b.setContentDescription(title + ". " + subtitle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        lp.bottomMargin = dp(10);
        parent.addView(b, lp);
        b.setOnClickListener(v -> {
            selected[0] = value;
            Toast.makeText(this, title + " selected", Toast.LENGTH_SHORT).show();
        });
    }

    private void startYoutubeJob(String url, boolean karaoke) {
        showProgress(karaoke ? "Creating karaoke" : "Creating MP3", "Preparing request…");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", url);
                body.put("master", karaoke);
                body.put("keep_backing_vocals", true);
                JSONObject response = postJson(apiBase() + (karaoke ? "/jobs/youtube-karaoke" : "/jobs/youtube-mp3"), body);
                activeJobId = response.getString("job_id");
                pollJob(activeJobId);
            } catch (Exception e) {
                showJobError(e);
            }
        });
    }

    private void startUploadJob(String path, String title) {
        showProgress(title, "Uploading audio…");
        executor.execute(() -> {
            try {
                JSONObject response = postMultipart(apiBase() + path, pendingAudio);
                activeJobId = response.getString("job_id");
                pollJob(activeJobId);
            } catch (Exception e) {
                showJobError(e);
            }
        });
    }

    private void showProgress(String title, String status) {
        runOnUiThread(() -> {
            LinearLayout p = page();
            addTopBar(p, true);

            TextView h = text(title, 27, TEXT, true);
            h.setPadding(0, dp(18), 0, dp(20));
            p.addView(h);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(20), dp(24), dp(20), dp(24));
            card.setBackground(rounded(CARD, 20));

            ProgressBar progress = new ProgressBar(this);
            card.addView(progress, new LinearLayout.LayoutParams(dp(54), dp(54)));

            TextView s = text(status, 17, TEXT, true);
            s.setId(1001);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(18), 0, dp(8));
            card.addView(s);

            TextView detail = text("You can leave this screen; the cloud worker keeps processing the job.", 13, MUTED, false);
            detail.setId(1002);
            detail.setGravity(Gravity.CENTER);
            card.addView(detail);

            p.addView(card);

            Button cancel = new Button(this);
            cancel.setText("Cancel");
            cancel.setTextColor(TEXT);
            cancel.setTextSize(15);
            cancel.setAllCaps(false);
            cancel.setBackground(rounded(CARD_ALT, 16));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            cp.topMargin = dp(16);
            p.addView(cancel, cp);
            cancel.setOnClickListener(v -> cancelJob());
        });
    }

    private void updateProgress(String status, String detail) {
        runOnUiThread(() -> {
            View sv = findViewById(1001);
            View dv = findViewById(1002);
            if (sv instanceof TextView) ((TextView) sv).setText(status);
            if (dv instanceof TextView) ((TextView) dv).setText(detail);
        });
    }

    private void pollJob(String jobId) {
        int ticks = 0;
        try {
            while (ticks < 3600) {
                JSONObject j = getJson(apiBase() + "/jobs/" + jobId);
                String status = j.optString("status", "processing");
                String stage = j.optString("stage", "Processing audio…");
                String detail = j.optString("detail", "Cloud processing is active.");
                updateProgress(stage, detail);

                if ("completed".equals(status)) {
                    String download = j.optString("download_url", apiBase() + "/jobs/" + jobId + "/download");
                    String name = j.optString("filename", "KaraokeStudio-result.mp3");
                    runOnUiThread(() -> showResult(download, name));
                    return;
                }
                if ("failed".equals(status)) {
                    throw new Exception(j.optString("error", "Processing failed"));
                }
                if ("cancelled".equals(status)) {
                    runOnUiThread(this::showHome);
                    return;
                }
                Thread.sleep(2000);
                ticks++;
            }
            throw new Exception("Processing timed out.");
        } catch (Exception e) {
            showJobError(e);
        }
    }

    private void cancelJob() {
        if (activeJobId == null) {
            showHome();
            return;
        }
        executor.execute(() -> {
            try {
                postJson(apiBase() + "/jobs/" + activeJobId + "/cancel", new JSONObject());
            } catch (Exception ignored) {}
            activeJobId = null;
            runOnUiThread(this::showHome);
        });
    }

    private void showResult(String downloadUrl, String filename) {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView tick = text("✓", 50, GREEN, true);
        tick.setGravity(Gravity.CENTER);
        tick.setPadding(0, dp(24), 0, dp(8));
        p.addView(tick);

        TextView h = text("Track ready", 28, TEXT, true);
        h.setGravity(Gravity.CENTER);
        p.addView(h);

        TextView n = text(filename, 14, MUTED, false);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, dp(24));
        p.addView(n);

        Button save = primaryButton("Save to Downloads");
        p.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        save.setOnClickListener(v -> downloadFile(downloadUrl, filename));

        Button home = new Button(this);
        home.setText("Back to home");
        home.setTextColor(TEXT);
        home.setTextSize(15);
        home.setAllCaps(false);
        home.setBackground(rounded(CARD_ALT, 16));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        hp.topMargin = dp(12);
        p.addView(home, hp);
        home.setOnClickListener(v -> showHome());
    }

    private void downloadFile(String url, String filename) {
        try {
            Uri u = Uri.parse(url.startsWith("http") ? url : apiBase() + url);
            DownloadManager.Request r = new DownloadManager.Request(u);
            r.setTitle(filename);
            r.setDescription("Saving audio from Karaoke Studio");
            r.setMimeType("audio/mpeg");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);
            Toast.makeText(this, "Saving to Downloads…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + friendlyError(e), Toast.LENGTH_LONG).show();
        }
    }

    private void showJobError(Exception e) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Could not finish the job")
                .setMessage(friendlyError(e))
                .setPositiveButton("Try again", (d, w) -> showHome())
                .show());
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? "Unknown network or processing error." : m;
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("GET");
        return readJson(c);
    }

    private JSONObject postJson(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        return readJson(c);
    }

    private JSONObject postMultipart(String url, Uri uri) throws Exception {
        String boundary = "----KaraokeStudio" + UUID.randomUUID();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(1024 * 128);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        String name = fileName(uri);
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream());
             InputStream in = getContentResolver().openInputStream(uri)) {
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "") + "\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }
        return readJson(c);
    }

    private JSONObject readJson(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (in == null) throw new Exception("Server returned HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 300) {
            try {
                JSONObject err = new JSONObject(sb.toString());
                throw new Exception(err.optString("detail", err.optString("error", "HTTP " + code)));
            } catch (org.json.JSONException ex) {
                throw new Exception("HTTP " + code + ": " + sb);
            }
        }
        return new JSONObject(sb.toString());
    }

    @Override
    public void onBackPressed() {
        showHome();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
