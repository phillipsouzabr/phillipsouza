package com.phillipsouza.gfp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    public static final String CHANNEL_ID = "gfp_native_alerts";
    private static final String PREFS_NAME = "gfp_native_prefs";
    private static final String BOOTSTRAP_CACHE_FILE = "gfp_bootstrap_v3.json";
    private static final String BASE_ORIGIN_URL = "https://phillipsouza.com/gfp-vault/";
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int NOTIFY_PERM_REQ = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingNotifyTitle = null;
    private String pendingNotifyBody = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createNotificationChannel();
        requestNotificationPermissionIfNeeded();
        scheduleBackgroundAlertWorker();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(isNetworkAvailable() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(0xFF0B0F19);
        webView.addJavascriptInterface(new GfpNativeBridge(this), "GFPNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (!url.contains("phillipsouza.com") && !url.contains("script.google.com") && !url.contains("googleusercontent.com")) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        return true;
                    }
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCb, FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = filePathCb;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // 1. Limpa qualquer cache HTML antigo para garantir que o APK sempre rode 100% a versão nativa autônoma embutida em assets/index.html (inclusive offline!)
        try {
            File oldCached = new File(getFilesDir(), "gfp_cached_index.html");
            if (oldCached.exists()) oldCached.delete();
        } catch (Exception ignored) {}

        String htmlContent = loadBestAvailableHtml();
        webView.loadDataWithBaseURL(BASE_ORIGIN_URL, htmlContent, "text/html", "UTF-8", BASE_ORIGIN_URL);
    }

    private String loadBestAvailableHtml() {
        try {
            InputStream assetStream = getAssets().open("index.html");
            return readStreamToString(assetStream);
        } catch (Exception e) {
            return "<html><body style='background:#0B0F19;color:#fff;font-family:sans-serif;padding:24px;'>Erro ao carregar GFP.</body></html>";
        }
    }

    private String readStreamToString(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alertas Financeiros GFP",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notificações de vencimentos, contas e lembretes do GFP");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_PERM_REQ);
            }
        }
    }

    private void scheduleBackgroundAlertWorker() {
        try {
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(GfpAlertWorker.class, 6, TimeUnit.HOURS).build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "gfp_periodic_alerts",
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
            );
        } catch (Exception ignored) {}
    }

    public static void dispatchNativeNotification(Context ctx, String title, String body) {
        try {
            Intent intent = new Intent(ctx, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_ps)
                    .setColor(0xFF2563EB)
                    .setContentTitle(title != null ? title : "GFP")
                    .setContentText(body != null ? body : "")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null ? body : ""))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int id = (title != null ? title.hashCode() : 1001);
                nm.notify(id, builder.build());
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFY_PERM_REQ) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingNotifyTitle != null) {
                    dispatchNativeNotification(this, pendingNotifyTitle, pendingNotifyBody != null ? pendingNotifyBody : "");
                    pendingNotifyTitle = null;
                    pendingNotifyBody = null;
                }
            } else {
                Toast.makeText(this, "⚠️ Ative as notificações do GFP nas configurações do Android.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class GfpNativeBridge {
        private final Context context;

        GfpNativeBridge(Context ctx) {
            this.context = ctx;
        }

        @JavascriptInterface
        public void showNotification(String title, String body) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingNotifyTitle = title != null ? title : "GFP";
                    pendingNotifyBody = body != null ? body : "";
                    runOnUiThread(() -> ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_PERM_REQ));
                    return;
                }
            }
            dispatchNativeNotification(context, title, body);
            runOnUiThread(() -> Toast.makeText(context, "🔔 Alerta Nativo GFP enviado!", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void saveBootstrapCache(String jsonPayload) {
            try {
                if (jsonPayload == null || jsonPayload.length() < 10) return;
                File cacheFile = new File(context.getFilesDir(), BOOTSTRAP_CACHE_FILE);
                try (FileOutputStream fos = new FileOutputStream(cacheFile, false)) {
                    fos.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String loadBootstrapCache() {
            try {
                File cacheFile = new File(context.getFilesDir(), BOOTSTRAP_CACHE_FILE);
                if (cacheFile.exists() && cacheFile.length() > 10) {
                    return readStreamToString(new FileInputStream(cacheFile));
                }
            } catch (Exception ignored) {}
            return "";
        }

        @JavascriptInterface
        public void syncSchedule(String scheduleJson) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString("schedule_json", scheduleJson != null ? scheduleJson : "[]").apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                String cleanName = (fileName != null && !fileName.isEmpty()) ? fileName : ("GFP_" + System.currentTimeMillis() + ".xlsx");
                String cleanMime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "application/octet-stream";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, cleanName);
                    values.put(MediaStore.Downloads.MIME_TYPE, cleanMime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(bytes);
                        }
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File outFile = new File(dir, cleanName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }
                }

                runOnUiThread(() -> Toast.makeText(context, "✅ Arquivo salvo em Downloads: " + cleanName, Toast.LENGTH_LONG).show());
                dispatchNativeNotification(context, "📥 Download Concluído (GFP)", "Salvo na pasta Downloads: " + cleanName);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(context, "Erro ao salvar arquivo: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}

