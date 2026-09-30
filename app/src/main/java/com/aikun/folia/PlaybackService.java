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
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

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

    private static final String CHANNEL_ID = "folia_playback";
    private static final int NOTIFICATION_ID = 42;

    /** 服务把 play/pause/prev/next 命令推回网页播放器的回调面（MainActivity 注册，Activity 销毁时清空）。 */
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

    static void dispatchCommand(String command) {
        MediaCommandSink sink = commandSink;
        if (sink != null) {
            sink.onMediaCommand(command);
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
        });
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Folia 播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台播放控制");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
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
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            nextHasTrack = o.optBoolean("hasTrack", false);
            nextPlaying = o.optBoolean("playing", false);
            nextTitle = o.optString("title", "");
            nextArtist = o.optString("artist", "");
            nextAlbum = o.optString("album", "");
            nextDuration = o.optLong("durationSec", 0);
            nextPosition = o.optLong("positionSec", 0);
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
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,
                        title == null || title.length() == 0 ? "Folia" : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationSec * 1000)
                .build();
        mediaSession.setMetadata(metadata);
        long positionMs = positionSec * 1000;
        PlaybackState.Builder state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        if (playing) {
            state.setState(PlaybackState.STATE_PLAYING, positionMs, 1f);
        } else {
            state.setState(PlaybackState.STATE_PAUSED, positionMs, 0f);
        }
        mediaSession.setPlaybackState(state.build());
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
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title == null || title.length() == 0 ? "Folia" : title)
                .setContentText(playing ? artist : (artist == null || artist.length() == 0 ? "已暂停" : artist + " · 已暂停"))
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
