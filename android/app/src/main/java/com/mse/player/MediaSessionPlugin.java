package com.mse.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

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
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), MediaPlaybackService.class));
        call.resolve();
    }

    static void sendAction(String action) {
        if (instance != null) {
            JSObject data = new JSObject();
            data.put("action", action);
            instance.notifyListeners("mediaAction", data);
        }
    }

    static void sendSeek(double seconds) {
        if (instance != null) {
            JSObject data = new JSObject();
            data.put("action", "seekto");
            data.put("seekTime", seconds);
            instance.notifyListeners("mediaAction", data);
        }
    }
}
