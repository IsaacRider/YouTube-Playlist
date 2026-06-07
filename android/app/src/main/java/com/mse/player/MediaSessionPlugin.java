package com.mse.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CapacitorPlugin(name = "MediaSession", permissions = {
    @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
})
public class MediaSessionPlugin extends Plugin {

    private static MediaSessionPlugin instance;
    private static final int NOTIF_PERMISSION_CODE = 1001;
    private boolean serviceStarted = false;

    @Override
    public void load() {
        instance = this;
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{ Manifest.permission.POST_NOTIFICATIONS }, NOTIF_PERMISSION_CODE);
            }
        }
    }

    private void ensureService() {
        if (serviceStarted) return;
        serviceStarted = true;
        Intent intent = new Intent(getContext(), MediaPlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
    }

    @PluginMethod
    public void updateMetadata(PluginCall call) {
        String title = call.getString("title", "");
        String artist = call.getString("artist", "");
        String album = call.getString("album", "");

        ensureService();
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.updateMetadata(title, artist, album);
        }
        call.resolve();
    }

    @PluginMethod
    public void updatePlayback(PluginCall call) {
        boolean playing = call.getBoolean("playing", false);
        double positionSec = call.getDouble("position", 0.0);
        double durationSec = call.getDouble("duration", 0.0);

        ensureService();
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.updatePlaybackState(
                    playing,
                    (long) (positionSec * 1000),
                    (long) (durationSec * 1000)
            );
        }
        call.resolve();
    }

    @PluginMethod
    public void nativePlay(PluginCall call) {
        String filename = call.getString("filename", "");
        ensureService();
        if (MediaPlaybackService.instance != null && !filename.isEmpty()) {
            MediaPlaybackService.instance.nativePlayFile(filename);
        }
        call.resolve();
    }

    @PluginMethod
    public void nativePause(PluginCall call) {
        ensureService();
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.nativePause();
        }
        call.resolve();
    }

    @PluginMethod
    public void nativeResume(PluginCall call) {
        ensureService();
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.nativeResume();
        }
        call.resolve();
    }

    @PluginMethod
    public void nativeSeek(PluginCall call) {
        double positionSec = call.getDouble("position", 0.0);
        ensureService();
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.nativeSeekTo((int)(positionSec * 1000));
        }
        call.resolve();
    }

    @PluginMethod
    public void nativeSetVolume(PluginCall call) {
        double volume = call.getDouble("volume", 1.0);
        ensureService();
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.nativeSetVolume((float) volume);
        }
        call.resolve();
    }

    @PluginMethod
    public void updateMediaTree(PluginCall call) {
        ensureService();
        if (MediaPlaybackService.instance == null) { call.resolve(); return; }

        List<String> tracksList = new ArrayList<>();
        Map<String, List<String>> playlistsMap = new LinkedHashMap<>();

        try {
            JSArray tracksArr = call.getArray("tracks");
            if (tracksArr != null) {
                for (int i = 0; i < tracksArr.length(); i++) {
                    tracksList.add(tracksArr.getString(i));
                }
            }
        } catch (Exception e) { /* ignore */ }

        try {
            JSObject plObj = call.getObject("playlists");
            if (plObj != null) {
                Iterator<String> keys = plObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = plObj.getJSONArray(key);
                    List<String> pl = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        pl.add(arr.getString(i));
                    }
                    playlistsMap.put(key, pl);
                }
            }
        } catch (Exception e) { /* ignore */ }

        MediaPlaybackService.instance.updateMediaTree(tracksList, playlistsMap);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (MediaPlaybackService.instance != null) {
            MediaPlaybackService.instance.nativeStop();
        }
        getContext().stopService(new Intent(getContext(), MediaPlaybackService.class));
        serviceStarted = false;
        call.resolve();
    }

    private static void evalJs(String js) {
        if (instance == null || instance.getBridge() == null) return;
        android.webkit.WebView wv = instance.getBridge().getWebView();
        if (wv == null) return;
        wv.post(() -> wv.evaluateJavascript(js, null));
    }

    static void sendAction(String action) {
        evalJs("window._mediaAction('" + action + "')");
    }

    static void sendSeek(double seconds) {
        evalJs("window._mediaAction('seekto'," + seconds + ")");
    }

    static void sendPlayTrack(String filename, String playlist) {
        String escaped = filename.replace("\\", "\\\\").replace("'", "\\'");
        String js = "window._mediaAction('playTrack',null,'" + escaped + "'";
        if (playlist != null) {
            js += ",'" + playlist.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        evalJs(js + ")");
    }

    static void sendNativeEvent(String event, long value) {
        evalJs("window._nativeAudioEvent('" + event + "'," + value + ")");
    }

    static void sendNativeEvent(String event, long value1, long value2) {
        evalJs("window._nativeAudioEvent('" + event + "'," + value1 + "," + value2 + ")");
    }
}
