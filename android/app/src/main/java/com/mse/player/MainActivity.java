package com.mse.player;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaSessionPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
