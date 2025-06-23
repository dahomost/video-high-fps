package com.daho.videohighfps;

import android.os.Bundle;
import android.os.Handler;
import android.view.TextureView;
import android.view.View;
import android.util.Log;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ⚠️ Avoid overriding Capacitor layout unless you're in full native control
        // This will replace the WebView. Use ONLY if this is a dedicated native screen.
        setContentView(R.layout.activity_preview);

        // ✅ Access overlay UI
        TextureView textureView = findViewById(R.id.textureView);
        GridOverlay gridOverlay = findViewById(R.id.gridOverlay);

        if (textureView == null) {
            Log.e("MainActivity", "TextureView not found in layout!");
            return;
        }

        if (gridOverlay == null) {
            Log.e("MainActivity", "GridOverlay not found in layout!");
            return;
        }

        // ✅ Optional: Hide grid overlay after 5 seconds
        new Handler().postDelayed(() -> {
            if (gridOverlay != null) {
                gridOverlay.setVisibility(View.GONE);
            }
        }, 5000);
    }
}
