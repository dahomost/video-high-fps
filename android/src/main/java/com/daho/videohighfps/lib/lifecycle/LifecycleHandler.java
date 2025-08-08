package com.daho.videohighfps.lib.lifecycle;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.app.ActivityCompat;

public class LifecycleHandler {

    private static final String TAG = "✅ LifecycleHandler";

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private boolean isDestroyed = false;

    private final Activity activity;

    public LifecycleHandler(Activity activity) {
        this.activity = activity;
    }

    public void startBackgroundThread() {
        Log.d(TAG, "⚙️ Starting background thread");
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        Log.d(TAG, "🧹 Stopping background thread");
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "⚠️ Error stopping background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    public Handler getBackgroundHandler() {
        return backgroundHandler;
    }

    public void handleOnDestroy() {
        Log.d(TAG, "☠️ handleOnDestroy()");
        isDestroyed = true;
        stopBackgroundThread();
    }

    public boolean isPluginDestroyed() {
        return isDestroyed;
    }

    public void cleanupResources(Runnable cleanupTask) {
        Log.d(TAG, "🧽 Cleaning up all plugin resources");
        stopBackgroundThread();
        if (cleanupTask != null) {
            cleanupTask.run();
        }
    }

    public boolean hasCameraPermission() {
        return ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasAudioPermission() {
        return ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
}
