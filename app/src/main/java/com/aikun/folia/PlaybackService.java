package com.aikun.folia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ④ 后台常驻播放：前台 Service，托着 MediaSession + 媒体通知卡片。
 *
 * 音频本体始终由 WebView 播放——本服务不碰音频流，也绝不申请音频焦点：焦点由 WebView 内
 * Chromium 媒体栈自持，服务侧若再申请会顶掉它，网页收 LOSS 立即自动暂停（"一点播放就停"）。
 * 职责只有：
 * 1. 拉起前台优先级，避免退到后台后播放被系统休眠/杀进程；
 * 2. 提供通知栏/锁屏传输控制（MediaSession 回调 → 命令派发回网页播放器）；
 * 3. 监听耳机拔出，把系统事件翻译成 pause 命令。
 *
 * 数据流：
 * - 网页端 --桥快照(桥线程写锁内)--> 本服务主线程 applySnapshotState（起停/通知）。
 * - 通知动作 --onStartCommand--> dispatchCommand；MediaSession 回调同样走 dispatchCommand。
 * - dispatchCommand --MainActivity 注入的 sink--> evaluateJavascript CustomEvent 回网页。
 */
public class PlaybackService extends Service {

    public static final String ACTION_PLAY = "com.aikun.folia.action.PLAY";
    public static final String ACTION_PAUSE = "com.aikun.folia.action.PAUSE";
    public static final String ACTION_PREVIOUS = "com.aikun.folia.action.PREVIOUS";
    public static final String ACTION_NEXT = "com.aikun.folia.action.NEXT";

    /**
     * 锁屏稳显渠道：IMPORTANCE_DEFAULT（无声）。IMPORTANCE_LOW 虽符合原生规范，但多数国产 ROM
     * 会把低重要性通知从锁屏折叠甚至隐去、状态栏不出图标——锁屏媒体卡片因此时有时无。
     * 渠道重要性创建后不可改，老用户迁移只能换渠道 ID；旧渠道删除避免设置页残留双条目。
     */
    private static final String CHANNEL_ID = "folia_playback_v2";
    private static final String LEGACY_CHANNEL_ID = "folia_playback";
    private static final int NOTIFICATION_ID = 42;

    /** 服务把 play/pause/prev/next/seek 命令推回网页播放器的回调面（MainActivity 注册，Activity 销毁时清空）。 */
    public interface MediaCommandSink {
        void onMediaCommand(String command);
    }

    private static volatile MediaCommandSink commandSink = null;
    private static volatile PlaybackService instance = null;

    /** 桥线程写入的最新快照 JSON；主线程消费（跨线程共享，锁保护）。 */
    private static final Object SNAPSHOT_LOCK = new Object();
    private static String pendingSnapshotJson = null;

    public static void setCommandSink(MediaCommandSink sink) {
        commandSink = sink;
    }

    /**
     * 命令派发：sink 收到的是 JSON 文本（{"command":"play"} 等）。网页端 JSON.parse 后按
     * command 字段路由；JSON 转义保证语音 query 里的引号/反斜杠不会拼坏派发表达式。
     */
    static void dispatchCommand(String command) {
        try {
            JSONObject o = new JSONObject();
            o.put("command", command);
            dispatchJson(o.toString());
        } catch (Exception ignored) {
        }
    }

    /**
     * 进度拖动：流体云/控制中心的进度条（ACTION_SEEK_TO）。MediaSession 回调给的是毫秒，
     * 快照契约用秒，这里换算后交网页端走与页内进度条同一的 seek 通道。
     */
    static void dispatchSeek(long positionSec) {
        try {
            JSONObject o = new JSONObject();
            o.put("command", "seek");
            o.put("positionSec", Math.max(0, positionSec));
            dispatchJson(o.toString());
        } catch (Exception ignored) {
        }
    }

    /** 语音搜索派发：{"command":"search","query":"...","focus":"vnd.android.cursor.item/audio"}。 */
    static void dispatchSearch(String query, String focus) {
        try {
            JSONObject o = new JSONObject();
            o.put("command", "search");
            o.put("query", query);
            if (focus != null && focus.length() > 0) {
                o.put("focus", focus);
            }
            dispatchJson(o.toString());
        } catch (Exception ignored) {
        }
    }

    private static void dispatchJson(String json) {
        MediaCommandSink sink = commandSink;
        if (sink != null) {
            sink.onMediaCommand(json);
        }
    }

    /** 桥入口：记录快照并按需拉起/刷新服务（桥线程调用，activity 传 getApplicationContext 即可）。 */
    public static void handleSnapshotJson(Context context, String json) {
        synchronized (SNAPSHOT_LOCK) {
            pendingSnapshotJson = json;
        }
        boolean hasTrack = false;
        try {
            hasTrack = new JSONObject(json == null ? "{}" : json).optBoolean("hasTrack", false);
        } catch (Exception ignored) {
        }
        PlaybackService service = instance;
        if (hasTrack && service == null) {
            Intent intent = new Intent(context, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } else if (service != null) {
            service.refreshFromSnapshot();
        }
    }

    // —— 快照状态（仅主线程读写） ——
    private boolean playing = false;
    private String title = "";
    private String artist = "";
    private String album = "";
    private long durationSec = 0;
    private long positionSec = 0;
    private String artworkUrl = "";
    private String currentLyricLine = "";

    /** 已加载/加载中的封面 URL（去重，失败不自动重试）；null = 从未加载。 */
    private String loadedArtworkUrl = null;
    private Bitmap artworkBitmap = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private MediaSession mediaSession;
    private BroadcastReceiver noisyReceiver = null;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mediaSession = new MediaSession(this, "FoliaPlayback");
        mediaSession.setActive(true);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                dispatchCommand("play");
            }

            @Override
            public void onPause() {
                dispatchCommand("pause");
            }

            @Override
            public void onSkipToPrevious() {
                dispatchCommand("prev");
            }

            @Override
            public void onSkipToNext() {
                dispatchCommand("next");
            }

            @Override
            public void onSeekTo(long pos) {
                dispatchSeek(pos / 1000);
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                // 语音助手（Google 助手/小艺等）："播放 xxx" 走这里。空 query（只喊了"播放"）
                // 退化为恢复播放；否则把搜索词交给网页端执行"搜索第一首并播放"。
                if (query == null || query.trim().length() == 0) {
                    dispatchCommand("play");
                    return;
                }
                String focus = extras == null ? null : extras.getString(MediaStore.EXTRA_MEDIA_FOCUS);
                dispatchSearch(query.trim(), focus);
            }
        });
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Folia 播放",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("后台播放控制");
            channel.setShowBadge(false);
            // 不设声音（显式置 null）：DEFAULT 只带来横幅+状态栏图标，配合 setOnlyAlertOnce
            // 仅通知首次出现时出横幅，之后的播放/暂停/切歌更新全部静默。
            channel.setSound(null, null);
            nm.createNotificationChannel(channel);
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID);
        }
        applySnapshotState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            dispatchCommand("play");
        } else if (ACTION_PAUSE.equals(action)) {
            dispatchCommand("pause");
        } else if (ACTION_PREVIOUS.equals(action)) {
            dispatchCommand("prev");
        } else if (ACTION_NEXT.equals(action)) {
            dispatchCommand("next");
        }
        applySnapshotState();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 划掉任务时继续播（后台常驻的意义所在）；网页端无曲时会经快照叫停本服务。
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        artworkExecutor.shutdownNow();
        artworkBitmap = null;
        unregisterNoisyReceiver();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    private void refreshFromSnapshot() {
        mainHandler.post(this::applySnapshotState);
    }

    private void applySnapshotState() {
        String json;
        synchronized (SNAPSHOT_LOCK) {
            json = pendingSnapshotJson;
        }
        boolean nextHasTrack = false;
        boolean nextPlaying = false;
        String nextTitle = "";
        String nextArtist = "";
        String nextAlbum = "";
        long nextDuration = 0;
        long nextPosition = 0;
        String nextArtworkUrl = "";
        String nextLyricLine = "";
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            nextHasTrack = o.optBoolean("hasTrack", false);
            nextPlaying = o.optBoolean("playing", false);
            nextTitle = o.optString("title", "");
            nextArtist = o.optString("artist", "");
            nextAlbum = o.optString("album", "");
            nextDuration = o.optLong("durationSec", 0);
            nextPosition = o.optLong("positionSec", 0);
            nextArtworkUrl = o.optString("artworkUrl", "");
            nextLyricLine = o.optString("currentLyricLine", "");
        } catch (Exception ignored) {
        }
        if (!nextHasTrack) {
            // 无在播曲目：撤下通知并停止，进程回退普通优先级（WebView 若仍在播则交由系统裁量）。
            playing = false;
            unregisterNoisyReceiver();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        boolean wasPlaying = playing;
        playing = nextPlaying;
        title = nextTitle;
        artist = nextArtist;
        album = nextAlbum;
        durationSec = Math.max(0, nextDuration);
        positionSec = Math.max(0, nextPosition);
        artworkUrl = nextArtworkUrl;
        currentLyricLine = nextLyricLine;
        loadArtworkIfNeeded();
        if (playing != wasPlaying) {
            if (playing) {
                registerNoisyReceiver();
            } else {
                unregisterNoisyReceiver();
            }
        }
        updateMediaSession();
        startForegroundWithNotification();
    }

    private void updateMediaSession() {
        if (mediaSession == null) {
            return;
        }
        // 播放中把"艺术家"一行换成实时歌词（流体云/控制中心媒体卡片直接读 MediaSession metadata）。
        boolean showLyric = playing && currentLyricLine != null && currentLyricLine.length() > 0;
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        title == null || title.length() == 0 ? "Folia" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, showLyric ? currentLyricLine : artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationSec * 1000);
        if (artworkBitmap != null && !artworkBitmap.isRecycled()) {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artworkBitmap);
        }
        mediaSession.setMetadata(metadataBuilder.build());
        long positionMs = positionSec * 1000;
        // ACTION_SEEK_TO 声明后流体云/控制中心才会显示可拖动进度条并派发 onSeekTo；
        // 位置与速度（1f/0f）随每次快照刷新，拖动基准由系统按速度外推。
        PlaybackState.Builder state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_PLAY_FROM_SEARCH);
        if (playing) {
            state.setState(PlaybackState.STATE_PLAYING, positionMs, 1f);
        } else {
            state.setState(PlaybackState.STATE_PAUSED, positionMs, 0f);
        }
        mediaSession.setPlaybackState(state.build());
    }

    /** 封面按 URL 变化才重新拉取解码：空 URL 清空旧封面；失败不自动重试（换曲/封面变化时自然再试）。 */
    private void loadArtworkIfNeeded() {
        String url = artworkUrl == null ? "" : artworkUrl;
        if (url.length() == 0) {
            if (loadedArtworkUrl != null && loadedArtworkUrl.length() > 0) {
                loadedArtworkUrl = "";
                artworkBitmap = null;
                updateMediaSession();
                startForegroundWithNotification();
            }
            return;
        }
        if (url.equals(loadedArtworkUrl)) {
            return;
        }
        loadedArtworkUrl = url;
        artworkExecutor.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(true);
                bitmap = BitmapFactory.decodeStream(connection.getInputStream());
            } catch (Exception ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final Bitmap loaded = bitmap;
            mainHandler.post(() -> {
                if (instance != this) {
                    return;
                }
                artworkBitmap = loaded;
                updateMediaSession();
                startForegroundWithNotification();
            });
        });
    }

    private void startForegroundWithNotification() {
        if (mediaSession == null) {
            return;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int playPauseIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playPauseLabel = playing ? "暂停" : "播放";

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        boolean showLyric = playing && currentLyricLine != null && currentLyricLine.length() > 0;
        String contentText = showLyric ? currentLyricLine
                : (playing ? artist
                : (artist == null || artist.length() == 0 ? "已暂停" : artist + " · 已暂停"));
        builder.setSmallIcon(R.mipmap.ic_launcher)
                // 媒体传输类别：ROM 据此把本通知识别为媒体卡片，参与锁屏媒体控件的排序与样式判定。
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setContentTitle(title == null || title.length() == 0 ? "Folia" : title)
                .setContentText(contentText)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "上一首", actionPi(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(
                        playPauseIcon, playPauseLabel, actionPi(playing ? ACTION_PAUSE : ACTION_PLAY, 2)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_next, "下一首", actionPi(ACTION_NEXT, 3)).build());

        if (artworkBitmap != null && !artworkBitmap.isRecycled()) {
            builder.setLargeIcon(artworkBitmap);
        }

        Notification.MediaStyle style = new Notification.MediaStyle();
        if (mediaSession != null) {
            style.setMediaSession(mediaSession.getSessionToken());
        }
        style.setShowActionsInCompactView(0, 1, 2);
        builder.setStyle(style);
        return builder.build();
    }

    private PendingIntent actionPi(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void registerNoisyReceiver() {
        if (noisyReceiver != null) {
            return;
        }
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 耳机拔出/断开蓝牙音频：习惯动作是暂停，避免外放突响。
                dispatchCommand("pause");
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
    }

    private void unregisterNoisyReceiver() {
        if (noisyReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        noisyReceiver = null;
    }
}
