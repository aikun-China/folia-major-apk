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
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    /** ③ 音频唤起导入：固定 token 的单文件会话。 */
    private static final String AUDIO_IMPORT_TOKEN = "audio-intent";
    /**
     * ⑤ 分块读取会话表（token → 文件流缓存）：audio/* 唤起是单文件，SAF 文件夹导入一次登记
     * 大量文件交替读取。条目常驻（保证已登记 token 始终可读），仅用访问序 LRU 控制「同时打开
     * 的流」数量：超限时只关闭最久未访问会话的流，下次读取按 streamOffset 自动重开 + skip 恢复。
     */
    private static final int MAX_OPEN_IMPORT_STREAMS = 3;
    private int folderSessionSeq = 0;
    private final Map<String, ImportSession> importSessions =
            new LinkedHashMap<String, ImportSession>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImportSession> eldest) {
                    if (size() > MAX_OPEN_IMPORT_STREAMS) {
                        closeStream(eldest.getValue());
                    }
                    return false;
                }
            };

    /** 单个待读文件的流缓存：顺序读优化（乱序才重开流 skip 到位）。 */
    private static final class ImportSession {
        final Uri uri;
        InputStream stream;
        long streamOffset = -1;

        ImportSession(Uri uri) {
            this.uri = uri;
        }
    }

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
        if (!(activity instanceof MainActivity)) {
            return null;
        }
        Uri uri = ((MainActivity) activity).consumePendingAudioUri();
        if (uri == null) {
            return null;
        }
        try {
            registerImportSession(uri, AUDIO_IMPORT_TOKEN);
            JSONObject info = new JSONObject();
            info.put("token", AUDIO_IMPORT_TOKEN);
            info.put("name", resolveAudioDisplayName(uri));
            info.put("size", resolveAudioSize(uri));
            String mime = activity.getContentResolver().getType(uri);
            info.put("mimeType", mime == null ? "" : mime);
            return info.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** ③/⑤ 分块读取会话文件，返回该区间 Base64（NO_WRAP）；流结束/失败/越界返回空串。 */
    @JavascriptInterface
    public String readAudioChunk(String token, long offset, int length) {
        if (token == null || token.length() == 0 || offset < 0 || length <= 0) {
            return "";
        }
        ImportSession session = importSessions.get(token);
        if (session == null) {
            return "";
        }
        try {
            // 缓存流顺序读：网页端按 offset 递增请求；乱序/新会话才重开流并 skip 到位，
            // 避免每块重开造成大文件 O(n²) I/O。
            if (session.stream == null || session.streamOffset != offset) {
                closeStream(session);
                InputStream stream = activity.getContentResolver().openInputStream(session.uri);
                if (stream == null) {
                    return "";
                }
                skipFully(stream, offset);
                session.stream = stream;
                session.streamOffset = offset;
            }
            int cap = Math.min(length, 4 * 1024 * 1024);
            byte[] buffer = new byte[cap];
            int read = session.stream.read(buffer);
            if (read <= 0) {
                closeStream(session);
                return "";
            }
            session.streamOffset = offset + read;
            return Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
        } catch (Exception e) {
            closeStream(session);
            return "";
        }
    }

    /** 登记一个待读会话；同 token 重复登记先关旧流（新会话替换旧会话）。 */
    private ImportSession registerImportSession(Uri uri, String token) {
        ImportSession existing = importSessions.remove(token);
        if (existing != null) {
            closeStream(existing);
        }
        ImportSession session = new ImportSession(uri);
        importSessions.put(token, session);
        return session;
    }

    private static void closeStream(ImportSession session) {
        if (session.stream != null) {
            try {
                session.stream.close();
            } catch (Exception ignored) {
            }
            session.stream = null;
            session.streamOffset = -1;
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

    /** ⑤ 请求用户选择音乐文件夹（SAF ACTION_OPEN_DOCUMENT_TREE）；选择结果经事件回调。 */
    @JavascriptInterface
    public boolean pickAudioFolder() {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).startFolderPicker();
            return true;
        }
        return false;
    }

    /**
     * ⑤ 列出待导入文件夹内的候选文件（消费 tree Uri，一次性）。
     * 深度优先递归枚举整个目录树（含子文件夹），按扩展名过滤出音频/歌词/封面
     * （与网页端 getSnapshotFileKind 的白名单对齐），返回
     * [{token,name,size,mimeType,relativePath}] JSON，relativePath 首段为所选文件夹名。
     * 深度上限 MAX_IMPORT_SCAN_DEPTH、文件数上限 MAX_IMPORT_SCAN_FILES，防止超大目录树
     * 拖垮桥线程。未选择/失败返回 null，无候选返回 "[]"。
     */
    @JavascriptInterface
    public String listAudioFolderEntries() {
        if (!(activity instanceof MainActivity)) {
            return null;
        }
        Uri treeUri = ((MainActivity) activity).consumePendingFolderTreeUri();
        if (treeUri == null) {
            return null;
        }
        clearFolderImportSessions();
        try {
            String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
            String rootName = resolveTreeDisplayName(treeUri, rootDocId);
            JSONArray entries = new JSONArray();
            collectFolderEntries(treeUri, rootDocId, rootName, 0, entries);
            return entries.length() == 0 ? "[]" : entries.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static final int MAX_IMPORT_SCAN_DEPTH = 6;
    private static final int MAX_IMPORT_SCAN_FILES = 500;

    private void collectFolderEntries(Uri treeUri, String parentDocId, String parentPath,
            int depth, JSONArray out) {
        if (depth > MAX_IMPORT_SCAN_DEPTH || out.length() >= MAX_IMPORT_SCAN_FILES) {
            return;
        }
        Cursor cursor = null;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            cursor = activity.getContentResolver().query(childrenUri, null, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                if (out.length() >= MAX_IMPORT_SCAN_FILES) {
                    break;
                }
                String name = safeColumn(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                String mime = safeColumn(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (name == null || mime == null) {
                    continue;
                }
                String docId = safeColumn(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                if (docId == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectFolderEntries(treeUri, docId, parentPath + "/" + name, depth + 1, out);
                    continue;
                }
                if (!isImportableFileName(name)) {
                    continue;
                }
                int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex) ? cursor.getLong(sizeIndex) : -1;
                String token = "folder-" + (++folderSessionSeq);
                registerImportSession(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), token);
                JSONObject entry = new JSONObject();
                entry.put("token", token);
                entry.put("name", name);
                entry.put("size", size);
                entry.put("mimeType", mime);
                entry.put("relativePath", parentPath + "/" + name);
                out.put(entry);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 关闭并清空上一轮文件夹枚举登记的会话（token 以 "folder-" 开头），防止会话表无界增长。 */
    private void clearFolderImportSessions() {
        Iterator<Map.Entry<String, ImportSession>> iterator = importSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ImportSession> entry = iterator.next();
            if (entry.getKey().startsWith("folder-")) {
                closeStream(entry.getValue());
                iterator.remove();
            }
        }
    }

    /** 查询所选目录的显示名，兜底从 tree document id（"primary:Music/Folia"）取路径末段。 */
    private String resolveTreeDisplayName(Uri treeUri, String rootDocId) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(treeUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (index >= 0 && cursor.getString(index) != null && cursor.getString(index).length() > 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        int colon = rootDocId.indexOf(':');
        String path = colon >= 0 ? rootDocId.substring(colon + 1) : rootDocId;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.length() == 0 ? "Music" : name;
    }

    private static String safeColumn(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 ? cursor.getString(index) : null;
    }

    /** 扩展名白名单对齐网页端 getSnapshotFileKind：音频/歌词（翻译歌词扩展名重合自动覆盖）/封面。 */
    private static boolean isImportableFileName(String name) {
        String lower = name.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        String extension = dot >= 0 ? lower.substring(dot + 1) : "";
        switch (extension) {
            case "mp3":
            case "flac":
            case "m4a":
            case "wav":
            case "ogg":
            case "opus":
            case "aac":
            case "lrc":
            case "vtt":
            case "ttml":
            case "qrc":
            case "yrc":
            case "krc":
            case "fia":
                return true;
            default:
                return lower.equals("cover.png") || lower.equals("cover.jpg") || lower.equals("cover.jpeg");
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
