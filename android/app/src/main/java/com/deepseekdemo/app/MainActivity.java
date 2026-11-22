package com.deepseekdemo.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * DeepSeek 网页版（chat-demo 构建产物）的安卓壳。
 *
 * 页面通过 https://appassets.androidplatform.net/ 提供：
 * - https 自定义域 = 安全上下文，录音（getUserMedia）、AudioContext 朗读才能用
 * - 资源来自 APK 内 assets，不走网络，不需要本地服务器
 */
public class MainActivity extends Activity {

    private static final String TAG = "DSDemo";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/index.html";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_AUDIO_PERM = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;
    private long lastBackPressed = 0L;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(isNightMode() ? 0xFF17181A : 0xFFFFFFFF);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);
        setContentView(root);

        configureWebView();

        webView.addJavascriptInterface(new NativeBridge(), "DeepSeekAndroid");
        webView.setWebViewClient(new ShellWebViewClient());
        webView.setWebChromeClient(new ShellChromeClient());
        webView.setDownloadListener((url, userAgent, disposition, mimetype, contentLength) -> {
            if (url == null || url.startsWith("blob:") || url.startsWith("data:")) {
                // blob/data 由注入脚本走 NativeBridge 保存
                return;
            }
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).getLastPathSegment());
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(req);
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                toast("下载失败：" + e.getMessage());
            }
        });

        int coreMajor = webViewCoreMajor();
        if (coreMajor > 0 && coreMajor < 105) {
            toast("系统 WebView 内核版本偏低（" + coreMajor + "），建议在应用商店更新 Android System WebView");
        }
        WebView.setWebContentsDebuggingEnabled(true);
        webView.loadUrl(START_URL);
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
    }

    /** 从 UA 里取 Chromium 主版本号，用于低版本内核提示 */
    private int webViewCoreMajor() {
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            int i = ua.indexOf("Chrome/");
            if (i < 0) return 0;
            int j = i + 7;
            int k = j;
            while (k < ua.length() && Character.isDigit(ua.charAt(k))) k++;
            return Integer.parseInt(ua.substring(j, k));
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 资源服务：把 /assets/www 当成 https 站点的根目录
    // ------------------------------------------------------------------

    private class ShellWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!ASSET_HOST.equals(uri.getHost())) return null;
            return serveAsset(uri.getPath());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (ASSET_HOST.equals(uri.getHost())) return false;
            openExternally(uri);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectShellScript(view);
        }
    }

    private WebResourceResponse serveAsset(String path) {
        if (path == null || path.isEmpty()) path = "/";
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) {
            return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
                    new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
        String assetPath = "www" + path;
        try {
            InputStream in = getAssets().open(assetPath);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mimeOf(path), null, 200, "OK", headers, in);
        } catch (Exception e) {
            Log.w(TAG, "asset not found: " + assetPath);
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("没有可以打开该链接的应用");
        }
    }

    // ------------------------------------------------------------------
    // 注入脚本（文件选择、blob 保存 等浏览器能力补齐）
    // ------------------------------------------------------------------

    private void injectShellScript(WebView view) {
        try (InputStream in = getResources().openRawResource(R.raw.inject)) {
            byte[] buf = new byte[in.available()];
            int read = in.read(buf);
            String js = new String(buf, 0, Math.max(read, 0), "UTF-8");
            view.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.w(TAG, "inject failed", e);
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient：麦克风授权 + 文件选择
    // ------------------------------------------------------------------

    private class ShellChromeClient extends WebChromeClient {

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            boolean wantsAudio = false;
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
            }
            if (!wantsAudio) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermissionRequest = request;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingPermissionRequest == request) pendingPermissionRequest = null;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            Log.d(TAG, "onShowFileChooser mode=" + (params == null ? -1 : params.getMode())
                    + " accept=" + (params == null ? null : java.util.Arrays.toString(params.getAcceptTypes())));
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                return true;
            } catch (Exception e) {
                filePathCallback = null;
                toast("无法打开文件选择器");
                return false;
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            Log.d(TAG, "[web] " + message.message() + " @" + message.lineNumber());
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            int clipCount = (data != null && data.getClipData() != null)
                    ? data.getClipData().getItemCount() : -1;
            // 某些机型/内核下 WebChromeClient.FileChooserParams.parseResult() 会返回 null
            // （实测某机型 + WebView 132：clipCount=2 但 uris=null），所以自己取 URI
            Uri[] uris = collectPickedUris(resultCode, data);
            Log.d(TAG, "onActivityResult code=" + resultCode + " dataNull=" + (data == null)
                    + " clipCount=" + clipCount + " uris=" + (uris == null ? "null" : String.valueOf(uris.length)));
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            } else {
                Log.w(TAG, "onActivityResult: callback is null (result dropped)");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 从 clipData / data 里取出所有选中的 URI；用户取消时返回 null */
    private static Uri[] collectPickedUris(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return null;
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return null;
        return uris.toArray(new Uri[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_AUDIO_PERM) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            PermissionRequest request = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (request != null) {
                if (granted) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    request.deny();
                    toast("没有麦克风权限，语音输入不可用");
                }
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ------------------------------------------------------------------
    // 返回键：先交给页面（关闭弹窗/菜单），连按两次退出
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackPressed < 2000) {
                finish();
                return true;
            }
            lastBackPressed = now;
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));",
                        null);
            }
            toast("再按一次退出");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------
    // 原生桥：分块保存 blob（导出会话 ZIP 用）
    // ------------------------------------------------------------------

    private class NativeBridge {
        private OutputStream out;
        private Uri target;
        private String name;
        private String displayPath;
        private int bytes;

        @JavascriptInterface
        public boolean blobBegin(String fileName, String mime) {
            try {
                closeQuietly();
                name = fileName == null || fileName.isEmpty() ? "export.zip" : fileName;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    target = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (target == null) return false;
                    out = getContentResolver().openOutputStream(target);
                    displayPath = "内部存储/Download/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    File file = new File(dir, name);
                    target = null;
                    out = new FileOutputStream(file);
                    displayPath = file.getAbsolutePath();
                }
                bytes = 0;
                return out != null;
            } catch (Exception e) {
                Log.w(TAG, "blobBegin failed", e);
                closeQuietly();
                return false;
            }
        }

        @JavascriptInterface
        public void blobChunk(String base64) {
            try {
                if (out == null) return;
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                out.write(data);
                bytes += data.length;
            } catch (Exception e) {
                Log.w(TAG, "blobChunk failed", e);
            }
        }

        @JavascriptInterface
        public void blobEnd() {
            final Uri savedUri;
            final String savedPath;
            final int savedBytes;
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
                if (target != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(target, cv, null, null);
                }
            } catch (Exception e) {
                Log.w(TAG, "blobEnd failed", e);
            } finally {
                savedUri = target;
                savedPath = displayPath;
                savedBytes = bytes;
                out = null;
                target = null;
            }
            runOnUiThread(() -> showSavedDialog(savedUri, savedPath, savedBytes));
        }

        @JavascriptInterface
        public void blobFailed(String reason) {
            closeQuietly();
            final String msg = "保存失败：" + reason;
            runOnUiThread(() -> toast(msg));
        }

        /** 页面主题变化时同步状态栏/导航栏配色与图标明暗 */
        @JavascriptInterface
        public void setThemeColor(String colorText, boolean isDark) {
            final int color = parseColor(colorText, isDark ? 0xFF17181A : 0xFFFFFFFF);
            pageIsDark = isDark;
            runOnUiThread(() -> applyChromeColor(color, isDark));
        }

        @JavascriptInterface
        public void toast(String message) {
            final String msg = message;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        private void closeQuietly() {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            out = null;
            target = null;
        }
    }

    /** 页面当前是否为深色（用于原生弹窗跟随页面主题） */
    private boolean pageIsDark = true;

    /** 保存完成后告知确切位置，并提供“打开” */
    private void showSavedDialog(Uri savedUri, String displayPath, int savedBytes) {
        if (displayPath == null) return;
        String size = savedBytes >= 1024 * 1024
                ? String.format("%.1f MB", savedBytes / 1048576f)
                : Math.max(1, savedBytes / 1024) + " KB";
        int themeRes = pageIsDark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, themeRes))
                .setTitle("已保存（" + size + "）")
                .setMessage(displayPath)
                .setNegativeButton("好", null);
        if (savedUri != null) {
            builder.setPositiveButton("打开", (d, w) -> openSavedFile(savedUri));
        }
        builder.show();
    }

    private void openSavedFile(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/zip")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("没有能打开该类文件的应用，用文件管理器进 内部存储/Download 查看即可");
        }
    }

    private void applyChromeColor(int color, boolean isDark) {
        try {
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
            View decor = getWindow().getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController c = decor.getWindowInsetsController();
                if (c != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(isDark ? 0 : mask, mask);
                }
            } else {
                int flags = decor.getSystemUiVisibility();
                int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                flags = isDark ? (flags & ~light) : (flags | light);
                decor.setSystemUiVisibility(flags);
            }
        } catch (Exception e) {
            Log.w(TAG, "applyChromeColor failed", e);
        }
    }

    /** 支持 #rgb / #rrggbb / rgb() / rgba() 与透明色兜底 */
    private static int parseColor(String text, int fallback) {
        if (text == null) return fallback;
        String s = text.trim().toLowerCase();
        try {
            if (s.startsWith("#")) {
                String hex = s.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                            + hex.charAt(2) + hex.charAt(2);
                }
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                }
                if (hex.length() == 8) {
                    return (int) Long.parseLong(hex, 16);
                }
            }
            if (s.startsWith("rgb")) {
                int open = s.indexOf('(');
                int close = s.indexOf(')');
                if (open > 0 && close > open) {
                    String[] parts = s.substring(open + 1, close).split(",");
                    if (parts.length >= 3) {
                        int r = Math.round(Float.parseFloat(parts[0].trim()));
                        int g = Math.round(Float.parseFloat(parts[1].trim()));
                        int b = Math.round(Float.parseFloat(parts[2].trim()));
                        int a = 255;
                        if (parts.length >= 4) {
                            a = Math.round(Float.parseFloat(parts[3].trim()) * 255f);
                        }
                        return android.graphics.Color.argb(a, r, g, b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("DeepSeekAndroid");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
