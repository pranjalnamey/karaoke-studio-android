package com.seekgodtakeall.karaokestudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 4401;
    private static final String CONVERTER_URL = "https://y2mate.gs/";

    private static final int BG = Color.rgb(8, 11, 20);
    private static final int CARD = Color.rgb(20, 25, 40);
    private static final int CARD_ALT = Color.rgb(27, 33, 51);
    private static final int TEXT = Color.rgb(246, 247, 251);
    private static final int MUTED = Color.rgb(166, 175, 194);
    private static final int ACCENT = Color.rgb(139, 124, 255);
    private static final int GREEN = Color.rgb(83, 211, 158);
    private static final int AMBER = Color.rgb(241, 185, 92);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Uri pendingAudio;
    private String pendingAction;
    private OfflineAudioEngine.CancelToken offlineCancelToken;
    private NeuralKaraokeEngine.CancelToken neuralCancelToken;
    private File currentResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
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
            b.setOnClickListener(v -> showHome());
            bar.addView(b, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = back ? dp(14) : 0;
        titleBox.addView(text("Karaoke Studio", 21, TEXT, true));
        titleBox.addView(text("Professional Offline AI · v3.1", 13, GREEN, false));
        bar.addView(titleBox, titleParams);

        Button settings = new Button(this);
        settings.setText("⚙");
        settings.setTextSize(18);
        settings.setTextColor(TEXT);
        settings.setBackground(rounded(CARD_ALT, 14));
        settings.setContentDescription("About offline mode");
        settings.setOnClickListener(v -> showOfflineInfo());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));

        parent.addView(bar);
    }

    private void showHome() {
        LinearLayout p = page();
        addTopBar(p, false);

        TextView hero = text("Your zero-cost mobile audio studio", 29, TEXT, true);
        hero.setPadding(0, dp(10), 0, dp(8));
        p.addView(hero);

        TextView desc = text(
                "Professional vocal separation now runs on your phone with HT-Demucs AI. No Railway server, no cloud credits and no processing bill.",
                16, MUTED, false);
        desc.setPadding(0, 0, 0, dp(22));
        p.addView(desc);

        addStatusCard(p);

        addActionCard(p, "▶", "YouTube → Karaoke",
                "Open Y2Mate for authorized audio, then import the MP3 and process it locally.", "yt_karaoke");
        addActionCard(p, "♫", "YouTube → MP3",
                "Open Y2Mate in your browser for content you own or are authorized to download.", "yt_mp3");
        addActionCard(p, "🎤", "Upload → Karaoke",
                "Use professional HT-Demucs AI to isolate and remove vocals on-device.", "upload_karaoke");
        addActionCard(p, "✦", "Master Audio",
                "Apply a local compressor, gain stage and soft limiter with no upload.", "master");

        TextView note = text("Professional offline AI", 18, TEXT, true);
        note.setPadding(0, dp(22), 0, dp(9));
        p.addView(note);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), dp(15), dp(16), dp(15));
        info.setBackground(rounded(CARD, 18));
        info.addView(text("Zero server cost", 15, GREEN, true));
        TextView body = text(
                "Karaoke uses a real HT-Demucs neural vocal-separation model through ONNX Runtime. The 166 MB AI model downloads once to your phone, then songs are processed locally.",
                13, MUTED, false);
        body.setPadding(0, dp(5), 0, 0);
        info.addView(body);
        p.addView(info);
    }

    private void addStatusCard(LinearLayout parent) {
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(outlined(CARD, Color.rgb(45, 76, 70), 16));

        TextView dot = text("●", 16, GREEN, false);
        status.addView(dot);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(10);
        copy.addView(text(NeuralKaraokeEngine.isModelInstalled(this) ? "Professional AI ready" : "Professional AI model required", 14, TEXT, true));
        copy.addView(text(NeuralKaraokeEngine.isModelInstalled(this) ? "HT-Demucs installed on this phone" : "One-time ~166 MB model download", 12, MUTED, false));
        status.addView(copy, cp);

        TextView free = text("FREE", 12, GREEN, true);
        free.setPadding(dp(10), dp(7), dp(10), dp(7));
        free.setBackground(rounded(Color.rgb(22, 51, 45), 12));
        status.addView(free);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(18);
        parent.addView(status, lp);
    }

    private void addActionCard(LinearLayout parent, String icon, String title, String subtitle, String action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(CARD, 20));
        card.setClickable(true);
        card.setFocusable(true);
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
        card.addView(text("›", 28, MUTED, false));

        card.setOnClickListener(v -> {
            if ("yt_karaoke".equals(action)) showYoutubeScreen(true);
            else if ("yt_mp3".equals(action)) showYoutubeScreen(false);
            else if ("upload_karaoke".equals(action)) chooseAudio("offline_karaoke");
            else if ("master".equals(action)) chooseAudio("offline_master");
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(card, lp);
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

    private void showYoutubeScreen(boolean karaoke) {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text(karaoke ? "YouTube → Karaoke" : "YouTube → MP3", 27, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(6));
        p.addView(h);

        TextView d = text(karaoke
                        ? "Paste a YouTube link for content you own or are authorized to process. The converter opens in your browser. After downloading the MP3, return here and import it."
                        : "Paste a YouTube link for content you own or are authorized to download. The converter opens in your browser.",
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
        p.addView(url, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        addInfoChip(p, karaoke
                ? "1. Open Y2Mate · 2. Download MP3 · 3. Return here · 4. Import MP3 · 5. Offline karaoke processing"
                : "The MP3 conversion is handled in your normal browser; Karaoke Studio itself does not scrape YouTube.");

        Button open = primaryButton("Open Y2Mate");
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        op.topMargin = dp(18);
        p.addView(open, op);
        open.setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (!isYoutubeUrl(value)) {
                url.setError("Paste a valid YouTube URL");
                return;
            }
            openExternalConverter(value);
        });

        if (karaoke) {
            Button importMp3 = new Button(this);
            importMp3.setText("Import downloaded MP3");
            importMp3.setTextColor(TEXT);
            importMp3.setTextSize(16);
            importMp3.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            importMp3.setAllCaps(false);
            importMp3.setBackground(outlined(CARD_ALT, ACCENT, 16));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            ip.topMargin = dp(12);
            p.addView(importMp3, ip);
            importMp3.setOnClickListener(v -> chooseAudio("offline_karaoke"));
        }

        TextView legal = text(
                "Only download or process media when you have the necessary rights or permission.",
                12, MUTED, false);
        legal.setPadding(0, dp(16), 0, 0);
        p.addView(legal);
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

    private boolean isYoutubeUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
        } catch (Exception e) {
            return false;
        }
    }

    private void openExternalConverter(String youtubeUrl) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("YouTube link", youtubeUrl));
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CONVERTER_URL)));
            Toast.makeText(this,
                    "Link copied. Paste it in Y2Mate, download the MP3, then return to Karaoke Studio.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Could not open browser")
                    .setMessage("Open " + CONVERTER_URL + " in your browser and paste the YouTube link.")
                    .setPositiveButton("OK", null)
                    .show();
        }
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
            try {
                getContentResolver().takePersistableUriPermission(pendingAudio, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}

            if ("offline_master".equals(pendingAction)) {
                confirmMaster();
            } else {
                confirmKaraoke();
            }
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

    private void confirmKaraoke() {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text("Create professional karaoke", 27, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(4));
        p.addView(h);

        TextView f = text(fileName(pendingAudio), 14, MUTED, false);
        f.setPadding(0, 0, 0, dp(18));
        p.addView(f);

        addInfoChip(p, "HT-Demucs AI isolates the vocal stem, then Karaoke Studio subtracts it from the original mix. Instruments keep their normal level far better than the old centre-cancel method.");

        if (!NeuralKaraokeEngine.isModelInstalled(this)) {
            TextView model = text(
                    "One-time setup: download the ~166 MB professional vocal model. It is stored on this phone and reused for every song.",
                    13, AMBER, false);
            model.setPadding(0, dp(14), 0, 0);
            p.addView(model);
        } else {
            TextView model = text("Professional AI model installed ✓", 13, GREEN, true);
            model.setPadding(0, dp(14), 0, 0);
            p.addView(model);
        }

        TextView note = text(
                "This model is designed for vocal removal/karaoke. It removes the vocal stem, so backing vocals may also be reduced when the AI classifies them as vocals.",
                12, MUTED, false);
        note.setPadding(0, dp(12), 0, 0);
        p.addView(note);

        Button go = primaryButton(NeuralKaraokeEngine.isModelInstalled(this)
                ? "Create with professional AI"
                : "Download AI model & create");
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        gp.topMargin = dp(18);
        p.addView(go, gp);
        go.setOnClickListener(v -> {
            if (NeuralKaraokeEngine.isModelInstalled(this)) startNeuralKaraoke();
            else confirmModelDownload();
        });
    }

    private void confirmModelDownload() {
        new AlertDialog.Builder(this)
                .setTitle("Download professional AI model?")
                .setMessage("Karaoke Studio will download the HT-Demucs vocal model once (~166 MB). There is no server processing fee. Wi-Fi is recommended. The model stays on your phone for future songs.")
                .setPositiveButton("Download", (d, w) -> downloadAiModelThenProcess())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void downloadAiModelThenProcess() {
        neuralCancelToken = new NeuralKaraokeEngine.CancelToken();
        showProgress("Setting up professional AI", "Starting model download…");
        executor.execute(() -> {
            try {
                NeuralKaraokeEngine.downloadModel(
                        this,
                        (stage, percent) -> updateProgress(stage, percent + "% · One-time model setup"),
                        neuralCancelToken);
                neuralCancelToken.check();
                runOnUiThread(this::startNeuralKaraoke);
            } catch (CancellationException e) {
                runOnUiThread(this::showHome);
            } catch (Exception e) {
                showJobError(e);
            }
        });
    }

    private void startNeuralKaraoke() {
        neuralCancelToken = new NeuralKaraokeEngine.CancelToken();
        showProgress("Creating professional karaoke", "Preparing HT-Demucs AI…");
        executor.execute(() -> {
            try {
                File result = NeuralKaraokeEngine.createKaraoke(
                        this,
                        pendingAudio,
                        (stage, percent) -> updateProgress(stage, percent + "% · Professional AI on this phone"),
                        neuralCancelToken);
                currentResult = result;
                runOnUiThread(() -> showLocalResult(result, false));
            } catch (CancellationException e) {
                runOnUiThread(this::showHome);
            } catch (OutOfMemoryError oom) {
                showJobError(new Exception("This phone ran out of memory while running HT-Demucs. Close other apps and retry."));
            } catch (Exception e) {
                showJobError(e);
            }
        });
    }

    private void confirmMaster() {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text("Master Audio Offline", 27, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(4));
        p.addView(h);

        TextView f = text(fileName(pendingAudio), 14, MUTED, false);
        f.setPadding(0, 0, 0, dp(18));
        p.addView(f);

        addInfoChip(p, "Local compression + make-up gain + soft limiter. Output is a 16-bit WAV.");

        Button go = primaryButton("Master locally");
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        gp.topMargin = dp(18);
        p.addView(go, gp);
        go.setOnClickListener(v -> startOfflineProcessing(true));
    }

    private void startOfflineProcessing(boolean master) {
        offlineCancelToken = new OfflineAudioEngine.CancelToken();
        showProgress("Mastering locally", "Preparing decoder…");

        executor.execute(() -> {
            try {
                OfflineAudioEngine.Listener listener = (stage, percent) ->
                        updateProgress(stage, percent + "% · Processing on this phone");

                File result = OfflineAudioEngine.master(this, pendingAudio, listener, offlineCancelToken);

                currentResult = result;
                runOnUiThread(() -> showLocalResult(result, true));
            } catch (CancellationException e) {
                runOnUiThread(this::showHome);
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

            TextView detail = text(
                    "Keep Karaoke Studio open while processing. No cloud service is being used.",
                    13, MUTED, false);
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
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            cp.topMargin = dp(16);
            p.addView(cancel, cp);
            cancel.setOnClickListener(v -> {
                if (offlineCancelToken != null) offlineCancelToken.cancel();
                if (neuralCancelToken != null) neuralCancelToken.cancel();
                cancel.setEnabled(false);
                cancel.setText("Cancelling…");
            });
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

    private void showLocalResult(File result, boolean master) {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView tick = text("✓", 50, GREEN, true);
        tick.setGravity(Gravity.CENTER);
        tick.setPadding(0, dp(24), 0, dp(8));
        p.addView(tick);

        TextView h = text(master ? "Master ready" : "Karaoke ready", 28, TEXT, true);
        h.setGravity(Gravity.CENTER);
        p.addView(h);

        TextView n = text(result.getName(), 14, MUTED, false);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, dp(24));
        p.addView(n);

        addInfoChip(p, master
                ? "Mastered locally. Nothing was uploaded to Railway."
                : "Separated locally with HT-Demucs AI. Nothing was uploaded to Railway.");

        Button save = primaryButton("Save WAV to Downloads");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        sp.topMargin = dp(16);
        p.addView(save, sp);
        save.setOnClickListener(v -> saveToDownloads(result));

        Button home = new Button(this);
        home.setText("Back to home");
        home.setTextColor(TEXT);
        home.setTextSize(15);
        home.setAllCaps(false);
        home.setBackground(rounded(CARD_ALT, 16));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        hp.topMargin = dp(12);
        p.addView(home, hp);
        home.setOnClickListener(v -> showHome());
    }

    private void saveToDownloads(File source) {
        executor.execute(() -> {
            Uri target = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Karaoke Studio");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IllegalStateException("Could not create the Downloads file.");

                try (InputStream in = new FileInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IllegalStateException("Could not open Downloads storage.");
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, done, null, null);

                Uri finalTarget = target;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Saved")
                        .setMessage("Saved to Downloads/Karaoke Studio.\n\n" + source.getName())
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Share", (d, w) -> shareSaved(finalTarget))
                        .show());
            } catch (Exception e) {
                if (target != null) {
                    try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
                }
                showJobError(e);
            }
        });
    }

    private void shareSaved(Uri uri) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("audio/wav");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share audio"));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open share menu.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showOfflineInfo() {
        LinearLayout p = page();
        addTopBar(p, true);

        TextView h = text("Offline Edition", 28, TEXT, true);
        h.setPadding(0, dp(8), 0, dp(10));
        p.addView(h);

        addInfoChip(p, "Processing cost: ₹0 server charge");
        addInfoChip(p, "Cloud backend: Not used");
        addInfoChip(p, "Karaoke engine: HT-Demucs neural vocal separation via ONNX Runtime");
        addInfoChip(p, NeuralKaraokeEngine.isModelInstalled(this)
                ? "Professional model: Installed"
                : "Professional model: One-time ~166 MB download required");

        TextView body = text(
                "The phone does the work, so processing time and battery use depend on the device. Y2Mate remains an external browser handoff only.",
                14, MUTED, false);
        body.setPadding(0, dp(18), 0, 0);
        p.addView(body);
    }

    private void showJobError(Exception e) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Could not finish the job")
                .setMessage(friendlyError(e))
                .setPositiveButton("OK", null)
                .setNegativeButton("Home", (d, w) -> showHome())
                .show());
    }

    private String friendlyError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "Unknown local processing error.";
        if (m.contains("No space left")) return "Your phone does not have enough free storage for the WAV output.";
        return m;
    }

    @Override
    public void onBackPressed() {
        showHome();
    }

    @Override
    protected void onDestroy() {
        if (offlineCancelToken != null) offlineCancelToken.cancel();
        if (neuralCancelToken != null) neuralCancelToken.cancel();
        executor.shutdownNow();
        super.onDestroy();
    }
}
