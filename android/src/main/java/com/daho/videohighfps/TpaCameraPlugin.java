package com.daho.videohighfps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.daho.videohighfps.lib.bridge.PluginBridgeHandler;
import com.daho.videohighfps.lib.camera.CameraSessionManager;
import com.daho.videohighfps.lib.lifecycle.LifecycleHandler;
import com.daho.videohighfps.lib.recording.RecordingController;
import com.daho.videohighfps.lib.ui.UIOverlayManager;
import com.getcapacitor.*;

import java.util.Arrays;
import java.util.Locale;

@CapacitorPlugin(name = "TpaCameraPlugin")
public class TpaCameraPlugin extends Plugin {

    private static final String TAG = "✅ TpaCameraPlugin";

    // External components
    private FrameLayout overlay;
    private TextureView textureView;
    private View blackPlaceholder;
    private View blackOverlayView;

    // Plugin state
    private boolean isRecording = false;
    private boolean isPaused = false;
    private Size selectedSize;
    private int videoFrameRate = 30;
    private String selectedCameraId;
    private String videoPath;
    private long sizeLimit = 0;

    // System & threading
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // PluginCall storage
    private PluginCall storedCall;

    // Helper classes
    private CameraSessionManager cameraSessionManager;
    private RecordingController recordingController;
    private UIOverlayManager uiOverlayManager;
    private LifecycleHandler lifecycleHandler;
    private PluginBridgeHandler pluginBridgeHandler;

    @Override
    public void load() {
        super.load();
        Log.d(TAG, "🔌 Plugin loaded");
        Activity activity = getActivity();
        Context context = getContext();

        lifecycleHandler = new LifecycleHandler(activity);
        cameraSessionManager = new CameraSessionManager(context);
        recordingController = new RecordingController(context);
        pluginBridgeHandler = new PluginBridgeHandler(activity, this);
        // textureView and overlay will be initialized during setupUI
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        Log.d(TAG, "🎥 startRecording() called");

        this.storedCall = call;
        this.cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);

        // Permissions check
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            return;
        }

        // Read plugin params
        this.videoFrameRate = call.getInt("fps", 240);
        String resolution = call.getString("resolution", "1080p");
        this.sizeLimit = call.getLong("sizeLimit", 0L);

        Log.d(TAG, "startRecording params: fps=" + videoFrameRate + ", resolution=" + resolution);

        try {
            // Clean up old state
            cleanupResources();
            lifecycleHandler.stopBackgroundThread();
            lifecycleHandler.startBackgroundThread();
            backgroundHandler = lifecycleHandler.getBackgroundHandler();

            selectedCameraId = getPreferredCameraId();
            cameraSessionManager.cameraManager = cameraManager;
            cameraSessionManager.selectedCameraId = selectedCameraId;
            cameraSessionManager.videoFrameRate = videoFrameRate;
            cameraSessionManager.activity = getActivity();
            cameraSessionManager.backgroundHandler = backgroundHandler;
            cameraSessionManager.rejectCallback = msg -> pluginBridgeHandler.rejectIfPossible(call, msg);
            cameraSessionManager.cleanupCallback = this::cleanupResources;
            cameraSessionManager.fallbackCallback = new CameraSessionManager.FallbackCallback() {
                @Override
                public void tryHighSpeedAgain() {
                    /* implement */ }

                @Override
                public void tryStandardSessionAgain() {
                    /* implement */ }
            };

            cameraSessionManager.selectOptimalConfiguration(resolution, videoFrameRate);
            selectedSize = cameraSessionManager.selectedSize;

            showCameraPreview();

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start recording", e);
            cleanupResources();
            lifecycleHandler.stopBackgroundThread();
            call.reject("Failed to start recording: " + e.getMessage());
        }
    }

    // Example of delegating to helper
    private void showCameraPreview() {
        Log.d(TAG, "🖥️ showCameraPreview()");

        Activity activity = getActivity();
        if (activity == null) {
            pluginBridgeHandler.rejectIfPossible(storedCall, "Activity is null");
            return;
        }

        blackOverlayView = new View(activity);
        blackOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        blackOverlayView.setBackgroundColor(0xFF000000);
        blackOverlayView.setAlpha(1f);

        FrameLayout root = activity.findViewById(android.R.id.content);
        root.addView(blackOverlayView);
        blackOverlayView.bringToFront();

        textureView = new TextureView(activity);
        overlay = new FrameLayout(activity);
        uiOverlayManager = new UIOverlayManager(activity, overlay, textureView);
        uiOverlayManager.setupUI(blackOverlayView);
        cameraSessionManager.textureView = textureView;
        cameraSessionManager.overlay = overlay;
        cameraSessionManager.previewSurface = previewSurface;
        cameraSessionManager.blackPlaceholder = blackPlaceholder;
        cameraSessionManager.blackOverlayView = blackOverlayView;

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (selectedSize != null) {
                    surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                    previewSurface = new Surface(surface);
                    cameraSessionManager.previewSurface = previewSurface;
                    cameraSessionManager.openCamera();
                } else {
                    pluginBridgeHandler.rejectIfPossible(storedCall, "Resolution not selected");
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
    }

    private void cleanupResources() {
        Log.d(TAG, "🧹 cleanupResources() called");
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
            recordingController.safeReleaseMediaRecorder();

            getActivity().runOnUiThread(() -> {
                if (overlay != null && blackPlaceholder != null) {
                    overlay.removeView(blackPlaceholder);
                }
                if (blackOverlayView != null) {
                    ((ViewGroup) getActivity().findViewById(android.R.id.content)).removeView(blackOverlayView);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "🧨 Error during cleanup", e);
        }
    }

    private String getPreferredCameraId() throws CameraAccessException {
        String[] cameraIds = cameraManager.getCameraIdList();
        for (String id : cameraIds) {
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return cameraIds[0]; // fallback
    }

    @PermissionCallback
    private void onCameraPermissionResult(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            startRecording(call);
        } else {
            call.reject("Camera permission denied");
        }
    }
}
