package com.daho.videohighfps;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import com.getcapacitor.BridgeActivity;
import com.google.mlkit.vision.pose.Pose;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "ONNX main activity";

    private static MainActivity instance;
    private static GridOverlay zoneOverlay;

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this; // ✅ Save static reference

        getWindow().getDecorView().postDelayed(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            params.gravity = Gravity.CENTER;

            zoneOverlay = new GridOverlay(this);
            addContentView(zoneOverlay, params);
        }, 2000);
    }

    // ✅ Safely called from OnnxPreChecking
    public void updatePoseOverlay(Pose pose, int imageWidth, int imageHeight) {
        if (zoneOverlay != null) {
            zoneOverlay.setPose(pose, imageWidth, imageHeight);
        } else {
            Log.w(TAG, "⚠️ zoneOverlay is null");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "✅ onPause - stopping monitoring");
        OnnxPreChecking.stopMonitoring();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "✅ onDestroy - stopping monitoring");
        OnnxPreChecking.stopMonitoring();
    }
}
