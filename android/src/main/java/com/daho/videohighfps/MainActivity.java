package com.daho.videohighfps;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Delay adding the overlay to ensure WebView and camera are fully loaded
        getWindow().getDecorView().postDelayed(() -> {
            PlayerZoneOverlay overlay = new PlayerZoneOverlay(this);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            params.gravity = Gravity.CENTER;

            addContentView(overlay, params);
        }, 2000); // Delay slightly to ensure camera is ready
    }
}
