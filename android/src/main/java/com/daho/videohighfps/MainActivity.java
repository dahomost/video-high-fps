package com.daho.videohighfps;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;

import com.getcapacitor.BridgeActivity;
import com.google.mlkit.vision.pose.Pose;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "TPA MAIN";

    private PlayerZoneOverlay zoneOverlay;
    private static PoseOverlayView poseOverlayView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().postDelayed(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            params.gravity = Gravity.CENTER;

            // 🟥 Overlay for red detection zone
            zoneOverlay = new PlayerZoneOverlay(this);
            addContentView(zoneOverlay, params);

            // 🔵 Overlay for pose drawing
            poseOverlayView = new PoseOverlayView(this);
            addContentView(poseOverlayView, params);

        }, 2000);
    }

    // ✅ Call this from plugin or checker to update drawing
    public static void updatePoseOverlay(Pose pose, int width, int height) {
        if (poseOverlayView != null) {
            poseOverlayView.setPose(pose, width, height);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "🛑 onPause - stopping monitoring");
        PlayerPresenceChecker.stopMonitoring();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "🛑 onDestroy - stopping monitoring");
        PlayerPresenceChecker.stopMonitoring();
    }
}
