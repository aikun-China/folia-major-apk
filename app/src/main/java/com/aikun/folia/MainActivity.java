package com.aikun.folia;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://music.aikun-bili.top";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_NOTIFICATION = 1002;

    // Returns "closed" after simulating an Escape keydown when the page declares a
    // keyboard window ([data-folia-keyboard-window="true"]), otherwise "none".
    // Dispatched on document so both document-level and window-level handlers receive it.
    private static final String CLOSE_OVERLAY_OR_NONE_SCRIPT =
            "(function(){try{"
                    + "if(!document.querySelector('[data-folia-keyboard-window=\"true\"]')){return 'none';}"
                    + "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true}));"
                    + "return 'closed';"
                    + "}catch(e){return 'none';}})()";

    private WebView webView;
    private FrameLayout rootLayout;
    private Chrome chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileUploadCallback;
    // 沉浸式全屏：由网页端设置开关驱动（网页端默认开启），onWindowFocusChanged 时重放。
    private boolean immersiveEnabled = false;
    // ③ audio/* 唤起：待消费的音频文件 Uri（onCreate 冷启动 / onNewIntent 热启动写入，桥一次性取走）。
    private Uri pendingAudioUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootLayout = new FrameLayout(this);
        webView = new WebView(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);

        // 冷启动：从启动 intent 提取待播放音频；进程恢复（savedInstanceState != null）时不重复导入。
        if (savedInstanceState == null) {
            pendingAudioUri = extractAudioUri(getIntent());
        }

        setupWebView();
        // ④ 后台播放：前台服务（MediaSession/通知动作/音频焦点/耳机拔出）→ 网页播放器的命令回传通道。
        PlaybackService.setCommandSink(command -> runOnUiThread(() -> {
            if (webView == null) {
                return;
            }
            webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('folia-android-media-command',{detail:'"
                            + command + "'}));", null);
        }));
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        chromeClient = new Chrome();
        webView.setWebChromeClient(chromeClient);
        webView.setWebViewClient(new FoliaClient());
        webView.setDownloadListener(new DL());
        // window.foliaAndroid：原生桥（版本信息 / APK 更新下载安装 / 后续沉浸式、音频唤起、后台播放）
        webView.addJavascriptInterface(new FoliaAndroidBridge(this), "foliaAndroid");
    }

    private class FoliaClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showOfflinePage(String.valueOf(request.getUrl()));
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && url.startsWith("http")) {
                CookieManager.getInstance().flush();
            }
        }
    }

    private void showOfflinePage(String failedUrl) {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>body{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;"
                + "font-family:sans-serif;background:#17141F;color:#EDEAF5;}"
                + "h1{font-size:20px;font-weight:600;margin:0 0 8px}p{font-size:14px;opacity:.6;margin:0 0 24px}"
                + "a{display:inline-block;padding:10px 32px;border-radius:999px;background:#7C6BFF;color:#fff;text-decoration:none;font-size:15px}</style></head>"
                + "<body><h1>\u7f51\u7edc\u8fde\u63a5\u4e0d\u53ef\u7528</h1><p>\u8bf7\u68c0\u67e5\u7f51\u7edc\u540e\u91cd\u8bd5</p>"
                + "<a href=\"" + failedUrl + "\">\u91cd\u65b0\u52a0\u8f7d</a></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", failedUrl);
    }

    private class Chrome extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(null);
            }
            fileUploadCallback = filePathCallback;
            try {
                Intent intent = fileChooserParams.createIntent();
                startActivityForResult(intent, REQ_FILE_CHOOSER);
            } catch (Exception e) {
                fileUploadCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            webView.setVisibility(View.GONE);
            rootLayout.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) {
                return;
            }
            rootLayout.removeView(customView);
            customView = null;
            webView.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // 全屏视频退出后恢复沉浸式（若开关开启）
            if (immersiveEnabled) {
                applyImmersiveMode();
            }
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        }
    }

    private class DL implements DownloadListener {

        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimetype, long contentLength) {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, fileName);
                if (userAgent != null) {
                    req.addRequestHeader("User-Agent", userAgent);
                }
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) {
                    req.addRequestHeader("Cookie", cookie);
                }
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(req);
                Toast.makeText(MainActivity.this, "\u5f00\u59cb\u4e0b\u8f7d\uff1a" + fileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 热启动（singleTask 复用实例）：记录新 Uri 后通知页面重新消费；页面加载完成前到达也安全，
        // 挂载钩子会兜底消费一次。
        pendingAudioUri = extractAudioUri(intent);
        if (pendingAudioUri != null && webView != null) {
            webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('folia-android-audio-intent'));", null);
        }
    }

    /** 仅接受 ACTION_VIEW 且带 data 的音频唤起 intent。 */
    private static Uri extractAudioUri(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return null;
        }
        return intent.getData();
    }

    /** 网页端桥入口：取走待消费的音频 Uri（一次性）。 */
    public Uri consumePendingAudioUri() {
        Uri uri = pendingAudioUri;
        pendingAudioUri = null;
        return uri;
    }

    /** 网页端桥入口：开关沉浸式全屏（bridge 线程调用，切到 UI 线程执行）。 */
    public void setImmersiveMode(boolean enabled) {
        if (immersiveEnabled == enabled) {
            return;
        }
        immersiveEnabled = enabled;
        runOnUiThread(this::applyImmersiveMode);
    }

    private void applyImmersiveMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }
            window.setDecorFitsSystemWindows(!immersiveEnabled);
            if (immersiveEnabled) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
            return;
        }
        View decor = window.getDecorView();
        if (immersiveEnabled) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 软键盘、系统对话框等会清掉 systemUiVisibility；重新聚焦时补放一次。
        if (hasFocus && immersiveEnabled) {
            applyImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chromeClient.onHideCustomView();
            return;
        }
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // Let the page close its topmost overlay first; only fall back to history
        // navigation or exiting when the page reports nothing was open.
        webView.evaluateJavascript(CLOSE_OVERLAY_OR_NONE_SCRIPT, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value != null && value.contains("closed")) {
                    return;
                }
                if (webView == null) {
                    return;
                }
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    MainActivity.super.onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        PlaybackService.setCommandSink(null);
        if (webView != null) {
            webView.loadUrl("about:blank");
            rootLayout.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
