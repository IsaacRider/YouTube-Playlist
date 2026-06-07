package com.mse.player;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaSessionPlugin.class);
        registerPlugin(YouTubeExtractorPlugin.class);
        super.onCreate(savedInstanceState);

        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) {
            WebSettings settings = wv.getSettings();
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
    }

    private void keepWebViewAlive() {
        WebView wv = getBridge() != null ? getBridge().getWebView() : null;
        if (wv != null) {
            wv.onResume();
            wv.resumeTimers();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebViewAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewAlive();
    }
}
