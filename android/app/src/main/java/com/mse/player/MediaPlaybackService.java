package com.mse.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MediaPlaybackService extends MediaBrowserServiceCompat {

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

    private List<String> allTracks = new ArrayList<>();
    private Map<String, List<String>> playlistsMap = new LinkedHashMap<>();
    private String browsingPlaylist = null;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mse:audio");
        wakeLock.acquire();

        mediaSession = new MediaSessionCompat(this, "MSEPlayer");
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setActive(true);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                updatePlaybackState(true, position, duration);
                MediaSessionPlugin.sendAction("play");
            }

            @Override
            public void onPause() {
                updatePlaybackState(false, position, duration);
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

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (mediaId != null && mediaId.startsWith("track:")) {
                    String filename = mediaId.substring(6);
                    MediaSessionPlugin.sendPlayTrack(filename, browsingPlaylist);
                }
            }
        });

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes audioAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        updatePlaybackState(false, position, duration);
                        MediaSessionPlugin.sendAction("pause");
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        updatePlaybackState(false, position, duration);
                        MediaSessionPlugin.sendAction("pause");
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        updatePlaybackState(true, position, duration);
                        MediaSessionPlugin.sendAction("play");
                    }
                })
                .build();
        audioManager.requestAudioFocus(audioFocusRequest);

        showInitialNotification();
        registerBluetoothAutoResume();
    }

    private void registerBluetoothAutoResume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                for (AudioDeviceInfo d : addedDevices) {
                    if (d.isSink() && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!isPlaying) MediaSessionPlugin.sendAction("play");
                        }, 1500);
                        break;
                    }
                }
            }
        }, new Handler(Looper.getMainLooper()));
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

    // --- MediaBrowserService ---

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();

        switch (parentId) {
            case "root":
                items.add(makeBrowsable("__ALL_TRACKS__", "All Tracks",
                        allTracks.size() + " tracks"));
                items.add(makeBrowsable("__PLAYLISTS__", "Playlists",
                        playlistsMap.size() + " playlists"));
                break;

            case "__ALL_TRACKS__":
                browsingPlaylist = null;
                for (String track : allTracks) {
                    String[] parsed = parseTrack(track);
                    items.add(makePlayable("track:" + track, parsed[1], parsed[0]));
                }
                break;

            case "__PLAYLISTS__":
                for (Map.Entry<String, List<String>> entry : playlistsMap.entrySet()) {
                    items.add(makeBrowsable("playlist:" + entry.getKey(),
                            entry.getKey(), entry.getValue().size() + " tracks"));
                }
                break;

            default:
                if (parentId.startsWith("playlist:")) {
                    String plName = parentId.substring(9);
                    browsingPlaylist = plName;
                    List<String> plTracks = playlistsMap.get(plName);
                    if (plTracks != null) {
                        for (String track : plTracks) {
                            String[] parsed = parseTrack(track);
                            items.add(makePlayable("track:" + track, parsed[1], parsed[0]));
                        }
                    }
                }
                break;
        }

        result.sendResult(items);
    }

    private MediaBrowserCompat.MediaItem makeBrowsable(String id, String title, String subtitle) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title);
        if (subtitle != null) desc.setSubtitle(subtitle);
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem makePlayable(String id, String title, String subtitle) {
        MediaDescriptionCompat.Builder desc = new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title);
        if (subtitle != null && !subtitle.isEmpty()) desc.setSubtitle(subtitle);
        return new MediaBrowserCompat.MediaItem(desc.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private String[] parseTrack(String filename) {
        String name = filename.replaceAll("(?i)\\.mp3$", "");
        name = name.replaceAll("^\\d{1,3}\\s*-\\s*", "");
        name = name.replaceAll("(?i)\\s*\\(Official\\s*(Music\\s*)?Video\\)", "");
        name = name.replaceAll("(?i)\\s*\\(Official\\s*Audio(\\s*Video)?\\)", "");
        name = name.replaceAll("(?i)\\s*\\[Official\\s*(Music\\s*)?Video\\]", "");
        name = name.replaceAll("(?i)\\s*\\(lyrics?\\)", "");
        name = name.replaceAll("(?i)\\s*\\[lyrics?\\]", "");
        name = name.trim();
        String[] parts = name.split("\\s*(?:--|–|-)\\s+", 2);
        if (parts.length == 2) {
            return new String[]{parts[0].trim(), parts[1].trim()};
        }
        return new String[]{"", name};
    }

    void updateMediaTree(List<String> tracks, Map<String, List<String>> playlists) {
        this.allTracks = tracks != null ? tracks : new ArrayList<>();
        this.playlistsMap = playlists != null ? playlists : new LinkedHashMap<>();
        notifyChildrenChanged("root");
        notifyChildrenChanged("__ALL_TRACKS__");
        notifyChildrenChanged("__PLAYLISTS__");
        for (String name : this.playlistsMap.keySet()) {
            notifyChildrenChanged("playlist:" + name);
        }
    }

    // --- Notification / Playback ---

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_PLAY":
                    updatePlaybackState(true, position, duration);
                    MediaSessionPlugin.sendAction("play");
                    break;
                case "ACTION_PAUSE":
                    updatePlaybackState(false, position, duration);
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
    public void onDestroy() {
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
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
