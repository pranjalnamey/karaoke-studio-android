package com.seekgodtakeall.karaokestudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 301;
    private static final String PREFS = "karaoke_studio";
    private static final String KEY_URL = "pc_url";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();

        String saved = prefs.getString(KEY_URL, "");
        if (saved == null || saved.trim().isEmpty()) {
            showServerDialog(true);
        } else {
            loadServer(saved);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 11, 20));

        webView = new WebView(this);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(webView, webParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("⚙");
        settingsButton.setTextSize(18);
        settingsButton.setAllCaps(false);
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setBackgroundColor(Color.argb(210, 25, 31, 52));
        FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        gearParams.gravity = Gravity.TOP | Gravity.END;
        gearParams.topMargin = dp(10);
        gearParams.rightMargin = dp(10);
        root.addView(settingsButton, gearParams);
        settingsButton.setOnClickListener(v -> showServerDialog(false));

        setContentView(root);
        configureWebView();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " KaraokeStudioAndroid/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.contains(":8787")) {
                    Toast.makeText(MainActivity.this,
                            "Could not reach your PC. Check that the PC engine is open and both devices are on the same Wi-Fi.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                intent.setType("audio/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker available.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Karaoke Studio")
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.addRequestHeader("User-Agent", userAgent);
                request.setMimeType(mimetype == null || mimetype.isEmpty() ? "audio/mpeg" : mimetype);
                request.setTitle("Karaoke track");
                request.setDescription("Downloading karaoke MP3");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "KaraokeStudio_" + System.currentTimeMillis() + ".mp3");
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(this, "Saving MP3 to Downloads…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browser);
            }
        });
    }

    private void showServerDialog(boolean required) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView help = new TextView(this);
        help.setText("Enter the PC address shown by Karaoke Studio PC + Mobile. Example: 192.168.1.15");
        help.setTextColor(Color.DKGRAY);
        help.setPadding(0, 0, 0, dp(12));
        box.addView(help);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.1.15");
        String existing = prefs.getString(KEY_URL, "");
        input.setText(displayAddress(existing));
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Connect to Karaoke Studio PC")
                .setView(box)
                .setPositiveButton("Connect", null)
                .setNegativeButton(required ? "Exit" : "Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String normalized = normalizeAddress(input.getText().toString());
                if (normalized == null) {
                    input.setError("Enter a valid local PC address, for example 192.168.1.15");
                    return;
                }
                prefs.edit().putString(KEY_URL, normalized).apply();
                loadServer(normalized);
                dialog.dismiss();
            });
            if (required) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> finish());
            }
        });
        dialog.setCancelable(!required);
        dialog.show();
    }

    private String displayAddress(String url) {
        if (url == null) return "";
        return url.replace("http://", "").replace("https://", "")
                .replace(":8787/mobile", "").replace(":8787/", "").replace(":8787", "");
    }

    private String normalizeAddress(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.replace("http://", "").replace("https://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.endsWith(":8787")) s = s.substring(0, s.length() - 5);
        if (!s.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}|[A-Za-z0-9._-]+")) return null;
        return "http://" + s + ":8787/mobile";
    }

    private void loadServer(String url) {
        webView.loadUrl(url);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
