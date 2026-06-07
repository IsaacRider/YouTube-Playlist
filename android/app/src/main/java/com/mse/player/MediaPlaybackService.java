package com.mse.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

public class MediaPlaybackService extends Service {

    private static final String CHANNEL_ID = "mse_playback";
    private static final int NOTIFICATION_ID = 1;

    static MediaSessionCompat mediaSession;
    static MediaPlaybackService instance;

    private String currentTitle = "";
    private String currentArtist = "";
    private String currentAlbum = "";
    private boolean isPlaying = false;
    private long duration = 0;
    private long position = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();

        mediaSession = new MediaSessionCompat(this, "MSEPlayer");
        mediaSession.setActive(true);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                MediaSessionPlugin.sendAction("play");
            }

            @Override
            public void onPause() {
                MediaSessionPlugin.sendAction("pause");
            }

            @Override
            public void onSkipToNext() {
                MediaSessionPlugin.sendAction("nexttrack");
            }

            @Override
            public void onSkipToPrevious() {
                MediaSessionPlugin.sendAction("previoustrack");
            }

            @Override
            public void onSeekTo(long pos) {
                MediaSessionPlugin.sendSeek(pos / 1000.0);
            }
        });

        showInitialNotification();
    }

    private void showInitialNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Music Subscription Escape")
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setSilent(true)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken()))
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_PLAY":
                    MediaSessionPlugin.sendAction("play");
                    break;
                case "ACTION_PAUSE":
                    MediaSessionPlugin.sendAction("pause");
                    break;
                case "ACTION_NEXT":
                    MediaSessionPlugin.sendAction("nexttrack");
                    break;
                case "ACTION_PREV":
                    MediaSessionPlugin.sendAction("previoustrack");
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        instance = null;
        super.onDestroy();
    }

    void updateMetadata(String title, String artist, String album) {
        this.currentTitle = title != null ? title : "";
        this.currentArtist = artist != null ? artist : "";
        this.currentAlbum = album != null ? album : "";

        if (mediaSession != null) {
            MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum);
            if (duration > 0) {
                meta.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
            }
            mediaSession.setMetadata(meta.build());
        }
        updateNotification();
    }

    void updatePlaybackState(boolean playing, long positionMs, long durationMs) {
        this.isPlaying = playing;
        this.position = positionMs;
        this.duration = durationMs;

        if (mediaSession != null) {
            long actions = PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE;

            PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(
                            playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                            positionMs,
                            playing ? 1.0f : 0f
                    )
                    .build();
            mediaSession.setPlaybackState(state);

            if (duration > 0) {
                MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
                mediaSession.setMetadata(meta.build());
            }
        }
        updateNotification();
    }

    private void updateNotification() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action prevAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_skip_previous, "Previous",
                buildActionIntent("ACTION_PREV")).build();

        NotificationCompat.Action playPauseAction;
        if (isPlaying) {
            playPauseAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_pause, "Pause",
                    buildActionIntent("ACTION_PAUSE")).build();
        } else {
            playPauseAction = new NotificationCompat.Action.Builder(
                    R.drawable.ic_play, "Play",
                    buildActionIntent("ACTION_PLAY")).build();
        }

        NotificationCompat.Action nextAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_skip_next, "Next",
                buildActionIntent("ACTION_NEXT")).build();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle.isEmpty() ? "Music Subscription Escape" : currentTitle)
                .setContentText(currentArtist)
                .setSubText(currentAlbum)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private PendingIntent buildActionIntent(String action) {
        Intent intent = new Intent(this, MediaPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, action.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows playback controls");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
