package com.aikun.folia;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

/**
 * JS bridge injected into the WebView as window.foliaAndroid.
 *
 * Conventions shared with the web side (src/vite-env.d.ts FoliaAndroidBridge):
 * - Every method runs on the WebView's JavaBridge thread, NOT the UI thread, and is
 *   synchronous.
 * - JSON-returning methods return null (or the idle snapshot) on failure so the web
 *   side can fall back via its JSON.parse try/catch.
 * - The web side always probes window.foliaAndroid existence before calling.
 */
public class FoliaAndroidBridge {

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String IDLE_PROGRESS_JSON = "{\"status\":\"idle\",\"received\":0,\"total\":0}";

    private final Activity activity;
    /** DownloadManager id of the in-flight/completed update APK, -1 when none. */
    private long updateDownloadId = -1;

    /** ③ 音频唤起导入会话：单一 token 即可（导入天然串行），新 consume 会替换上一会话并关流。 */
    private static final String AUDIO_IMPORT_TOKEN = "audio-intent";
    private Uri audioImportUri = null;
    private InputStream audioImportStream = null;
    private long audioImportStreamOffset = -1;

    public FoliaAndroidBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String getAppInfo() {
        try {
            PackageInfo pi = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            JSONObject info = new JSONObject();
            info.put("versionName", pi.versionName == null ? "" : pi.versionName);
            info.put("versionCode", versionCode);
            info.put("packageName", activity.getPackageName());
            return info.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public boolean downloadUpdate(String url, String fileName) {
        if (url == null || url.length() == 0) {
            return false;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(APK_MIME);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String safeName = (fileName == null || fileName.trim().length() == 0)
                    ? "folia-update.apk" : fileName.trim();
            // App-private external dir: no storage permission needed, and DownloadManager
            // still serves it back as a grantable content:// URI.
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, safeName);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) {
                request.addRequestHeader("Cookie", cookie);
            }
            String userAgent = System.getProperty("http.agent");
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            updateDownloadId = dm.enqueue(request);
            return updateDownloadId > 0;
        } catch (Exception e) {
            updateDownloadId = -1;
            return false;
        }
    }

    @JavascriptInterface
    public String getDownloadProgress() {
        if (updateDownloadId < 0) {
            return IDLE_PROGRESS_JSON;
        }
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor cursor = null;
        try {
            cursor = dm.query(new DownloadManager.Query().setFilterById(updateDownloadId));
            if (cursor == null || !cursor.moveToFirst()) {
                return IDLE_PROGRESS_JSON;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long received = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            String state;
            switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    state = "success";
                    break;
                case DownloadManager.STATUS_FAILED:
                    state = "failed";
                    break;
                case DownloadManager.STATUS_PAUSED:
                    state = "paused";
                    break;
                case DownloadManager.STATUS_RUNNING:
                    state = "running";
                    break;
                default:
                    state = "idle";
                    break;
            }
            return "{\"status\":\"" + state + "\",\"received\":" + received + ",\"total\":" + total + "}";
        } catch (Exception e) {
            return IDLE_PROGRESS_JSON;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @JavascriptInterface
    public boolean installDownloadedUpdate() {
        if (updateDownloadId < 0) {
            return false;
        }
        // API 26+ requires "install unknown apps" to be granted for this app first.
        // Land the user directly on the toggle page; the web dialog shows a hint and
        // the user retries once the permission is on.
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(settings);
            } catch (Exception ignored) {
            }
            return false;
        }
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(updateDownloadId);
            if (uri == null) {
                return false;
            }
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean cancelUpdateDownload() {
        if (updateDownloadId < 0) {
            return false;
        }
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            int removed = dm.remove(new long[]{updateDownloadId});
            updateDownloadId = -1;
            return removed > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Immersive fullscreen toggle, driven by the web settings switch. Delegates to the Activity. */
    @JavascriptInterface
    public void setImmersiveMode(boolean enabled) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setImmersiveMode(enabled);
        }
    }

    /** ③ 消费待处理的音频唤起 intent，返回 {token,name,size,mimeType} JSON；无待处理时返回 null。 */
    @JavascriptInterface
    public String consumePendingAudioIntent() {
        closeAudioImportStream();
        audioImportUri = null;
        if (!(activity instanceof MainActivity)) {
            return null;
        }
        Uri uri = ((MainActivity) activity).consumePendingAudioUri();
        if (uri == null) {
            return null;
        }
        try {
            JSONObject info = new JSONObject();
            info.put("token", AUDIO_IMPORT_TOKEN);
            info.put("name", resolveAudioDisplayName(uri));
            info.put("size", resolveAudioSize(uri));
            String mime = activity.getContentResolver().getType(uri);
            info.put("mimeType", mime == null ? "" : mime);
            audioImportUri = uri;
            return info.toString();
        } catch (Exception e) {
            audioImportUri = null;
            return null;
        }
    }

    /** ③ 分块读取唤起的音频，返回该区间 Base64（NO_WRAP）；流结束/失败/越界返回空串。 */
    @JavascriptInterface
    public String readAudioChunk(String token, long offset, int length) {
        if (audioImportUri == null || !AUDIO_IMPORT_TOKEN.equals(token) || offset < 0 || length <= 0) {
            return "";
        }
        try {
            // 缓存流顺序读：网页端按 offset 递增请求；乱序/新会话才重开流并 skip 到位，
            // 避免每块重开造成大文件 O(n²) I/O。
            if (audioImportStream == null || audioImportStreamOffset != offset) {
                closeAudioImportStream();
                InputStream stream = activity.getContentResolver().openInputStream(audioImportUri);
                if (stream == null) {
                    return "";
                }
                skipFully(stream, offset);
                audioImportStream = stream;
                audioImportStreamOffset = offset;
            }
            int cap = Math.min(length, 4 * 1024 * 1024);
            byte[] buffer = new byte[cap];
            int read = audioImportStream.read(buffer);
            if (read <= 0) {
                closeAudioImportStream();
                return "";
            }
            audioImportStreamOffset = offset + read;
            return Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
        } catch (Exception e) {
            closeAudioImportStream();
            return "";
        }
    }

    private void closeAudioImportStream() {
        if (audioImportStream != null) {
            try {
                audioImportStream.close();
            } catch (Exception ignored) {
            }
            audioImportStream = null;
            audioImportStreamOffset = -1;
        }
    }

    private static void skipFully(InputStream stream, long amount) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (stream.read() < 0) {
                return;
            } else {
                remaining--;
            }
        }
    }

    private String resolveAudioDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.getString(index) != null) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "audio-import" : last;
    }

    private long resolveAudioSize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    /** ④ 推送播放快照给前台服务（通知卡片/MediaSession 的数据源）；解析失败按无曲目处理。 */
    @JavascriptInterface
    public void setPlaybackSnapshot(String snapshotJson) {
        PlaybackService.handleSnapshotJson(activity.getApplicationContext(), snapshotJson);
    }

    /** ④ 打开电池优化白名单请求弹窗（失败退回系统电池优化列表页）。 */
    @JavascriptInterface
    public void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }

    /** ④ 打开系统应用详情页（自启动/后台限制的总入口）。 */
    @JavascriptInterface
    public void openAppDetailsSettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}
