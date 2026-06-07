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
        getContext().stopService(new Intent(getContext(), MediaPlaybackService.class));
        serviceStarted = false;
        call.resolve();
    }

    static void sendAction(String action) {
        if (instance != null) {
            JSObject data = new JSObject();
            data.put("action", action);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (instance != null) instance.notifyListeners("mediaAction", data);
            });
        }
    }

    static void sendSeek(double seconds) {
        if (instance != null) {
            JSObject data = new JSObject();
            data.put("action", "seekto");
            data.put("seekTime", seconds);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (instance != null) instance.notifyListeners("mediaAction", data);
            });
        }
    }

    static void sendPlayTrack(String filename, String playlist) {
        if (instance != null) {
            JSObject data = new JSObject();
            data.put("action", "playTrack");
            data.put("filename", filename);
            if (playlist != null) data.put("playlist", playlist);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (instance != null) instance.notifyListeners("mediaAction", data);
            });
        }
    }
}
