package com.daho.videohighfps.lib.bridge;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginHandle;

public class PluginBridgeHandler {

    private static final String TAG = "✅ PluginBridgeHandler";

    private final Activity activity;
    private final Plugin plugin;

    public PluginBridgeHandler(Activity activity, Plugin plugin) {
        this.activity = activity;
        this.plugin = plugin;
    }

    public void resolveResultToWeb(PluginCall call, JSObject result) {
        if (call == null) {
            Log.w(TAG, "resolveResultToWeb: call is null");
            return;
        }
        Log.d(TAG, "✅ Resolving result to web: " + result.toString());
        call.resolve(result);
    }

    public void rejectIfPossible(PluginCall call, String errorMessage) {
        if (call != null && !call.isReleased()) {
            Log.w(TAG, "❌ Rejecting call: " + errorMessage);
            call.reject(errorMessage);
        } else {
            Log.e(TAG, "❌ Call is null or already released, cannot reject: " + errorMessage);
        }
    }

    public void restoreWebView(FrameLayout rootView, View cameraOverlayView) {
        activity.runOnUiThread(() -> {
            Log.d(TAG, "🧩 Restoring Capacitor WebView");
            if (rootView != null && cameraOverlayView != null) {
                rootView.removeView(cameraOverlayView);
            }
        });
    }
}
