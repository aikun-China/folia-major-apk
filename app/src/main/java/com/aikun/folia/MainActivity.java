package com.aikun.folia;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://music.aikun-bili.top";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_NOTIFICATION = 1002;
    private static final int REQ_FOLDER_PICKER = 1003;

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
    // ⑤ 文件夹导入：待消费的 SAF 目录 tree Uri（onActivityResult 写入，桥一次性取走）。
    private Uri pendingFolderTreeUri = null;

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
        // ④ 后台播放：前台服务（MediaSession/通知动作/耳机拔出/语音搜索）→ 网页播放器的命令
        // 回传通道。payload 是 JSON 文本；quote 生成安全的 JS 字符串字面量，页面侧 JSON.parse。
        // 切勿在表达式里先行 JSON.parse：页面侧会对 detail 再 parse 一次，双 parse 会把
        // 对象 coerce 成 "[object Object]" 抛错，整条命令链（播放/暂停/切歌/进度）静默失灵。
        PlaybackService.setCommandSink(payload -> runOnUiThread(() -> {
            if (webView == null) {
                return;
            }
            webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('folia-android-media-command',{detail:"
                            + JSONObject.quote(payload) + "}));", null);
        }));
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
        if (savedInstanceState != null) {
            // 进程恢复：先还原沉浸式标志（onWindowFocusChanged 依赖它重放），再恢复页面。
            immersiveEnabled = savedInstanceState.getBoolean("immersive", false);
            webView.restoreState(savedInstanceState);
            if (immersiveEnabled) {
                applyImmersiveMode();
            }
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
            // 页面（含进程恢复后 restoreState 的重载）就绪：通知网页端回放沉浸式期望值。
            // 期望值存在网页 localStorage 里，原生 immersiveEnabled 标志恢复初期恒 false，
            // 只能由网页端回放告知。
            view.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('folia-android-page-ready'));", null);
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
            if (resultCode == RESULT_OK && data != null) {
                // 网页端 multiple 输入的多选结果在 ClipData 里，data.getData() 此时为 null；
                // 丢掉 ClipData 会让网页收到空列表，表现为"选完文件毫无反应"。
                ClipData clip = data.getClipData();
                if (clip != null) {
                    int count = clip.getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        } else if (requestCode == REQ_FOLDER_PICKER) {
            // ⑤ SAF 文件夹选择结果：记录 tree Uri 后通知页面开始消费；用户取消（RESULT_CANCELED）
            // 也派发 cancel 事件，网页端据此结束等待、复位导入状态。
            Uri treeUri = null;
            if (resultCode == RESULT_OK && data != null) {
                treeUri = data.getData();
            }
            if (treeUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                pendingFolderTreeUri = treeUri;
            }
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('folia-android-folder-picked',{detail:'"
                                + (treeUri != null ? "ok" : "cancel") + "'}));", null);
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

    /** ⑤ 网页端桥入口：拉起系统文件夹选择器（SAF ACTION_OPEN_DOCUMENT_TREE）。 */
    public void startFolderPicker() {
        runOnUiThread(() -> {
            try {
                startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER_PICKER);
            } catch (Exception e) {
                // 启动失败（无文件管理器等）也要补发 cancel，避免网页端等待悬挂。
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.dispatchEvent(new CustomEvent('folia-android-folder-picked',{detail:'cancel'}));",
                            null);
                }
            }
        });
    }

    /** ⑤ 网页端桥入口：取走待消费的文件夹 tree Uri（一次性）。 */
    public Uri consumePendingFolderTreeUri() {
        Uri uri = pendingFolderTreeUri;
        pendingFolderTreeUri = null;
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
        // 打孔屏/刘海屏：沉浸时窗口必须延伸进挖孔区域（SHORT_EDGES），否则系统把状态栏区域
        // letterbox 成一条黑边——最近任务快照同样带黑边。非沉浸时恢复默认避让。
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = immersiveEnabled
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(lp);
        }
        // 沉浸时系统栏底色透明：下滑瞬变呼出（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）不闪
        // 主题色底块；退出沉浸时还原主题色（getColor 随 values/values-night 自动切换）。
        if (immersiveEnabled) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        } else {
            window.setStatusBarColor(getColor(R.color.status_bar));
            window.setNavigationBarColor(getColor(R.color.nav_bar));
        }
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
        outState.putBoolean("immersive", immersiveEnabled);
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
