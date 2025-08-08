package com.daho.videohighfps.lib.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.daho.videohighfps.OnnxPreChecking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class CameraSessionManager {

    private static final String TAG = "✅ Camera plugin -=>";
    private final Object cameraLock = new Object();

    // External dependencies (inject or set from main class)
    public CameraDevice cameraDevice;
    public CameraManager cameraManager;
    public TextureView textureView;
    public Surface previewSurface;
    public FrameLayout overlay;
    public View blackPlaceholder;
    public View blackOverlayView;
    public Size selectedSize;
    public String selectedCameraId;
    public int videoFrameRate;
    public Handler backgroundHandler;
    public Activity activity;
    public Runnable cleanupCallback;
    public FallbackCallback fallbackCallback;
    public RejectCallback rejectCallback;

    // Interfaces for callbacks (to be implemented in TpaCameraPlugin)
    public interface RejectCallback {
        void reject(String message);
    }

    public interface FallbackCallback {
        void tryHighSpeedAgain();

        void tryStandardSessionAgain();
    }

    public void openCamera() {
        if (activity == null || cameraManager == null || selectedCameraId == null) {
            rejectCallback.reject("Invalid camera init context");
            cleanupCallback.run();
            return;
        }

        try {
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    synchronized (cameraLock) {
                        Log.d(TAG, "onOpened() -> Assigning cameraDevice.");
                        cameraDevice = camera;
                    }

                    backgroundHandler.postDelayed(() -> {
                        try {
                            synchronized (cameraLock) {
                                if (cameraDevice == null) {
                                    rejectCallback.reject("Camera disconnected before session start");
                                    return;
                                }

                                if (videoFrameRate >= 120) {
                                    startHighSpeedCaptureSession();
                                } else {
                                    startStandardCaptureSession();
                                }
                            }
                        } catch (Exception e) {
                            rejectCallback.reject(e.getMessage());
                            cleanupCallback.run();
                        }
                    }, 400);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    cleanupCallback.run();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    rejectCallback.reject(getCameraErrorMessage(error));
                    cleanupCallback.run();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            rejectCallback.reject("Camera access failed: " + e.getMessage());
            cleanupCallback.run();
        }
    }

    public void startHighSpeedCaptureSession() {
        synchronized (cameraLock) {
            try {
                Log.d(TAG, "startHighSpeedCaptureSession() started");

                if (cameraDevice == null || selectedSize == null || cameraManager == null || selectedCameraId == null) {
                    Log.e(TAG, "Missing camera setup state");
                    return;
                }

                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
                StreamConfigurationMap configMap = characteristics
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (configMap == null) {
                    Log.e(TAG, "StreamConfigurationMap is null");
                    tryLowerFpsFallback();
                    return;
                }

                Range<Integer>[] fpsRanges = characteristics
                        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                boolean highSpeedSupported = Arrays.stream(fpsRanges).anyMatch(r -> r.getUpper() >= 120);
                if (!highSpeedSupported) {
                    Log.w(TAG, "High speed not supported, falling back to standard");
                    fallbackCallback.tryStandardSessionAgain();
                    return;
                }

                SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
                if (surfaceTexture == null) {
                    Log.e(TAG, "SurfaceTexture is null");
                    return;
                }

                surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                previewSurface = new Surface(surfaceTexture);
                List<Surface> surfaces = List.of(previewSurface);

                cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                synchronized (cameraLock) {
                                    try {
                                        CaptureRequest.Builder builder = cameraDevice
                                                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                        builder.addTarget(previewSurface);
                                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                new Range<>(videoFrameRate, videoFrameRate));
                                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                        Log.d(TAG, "High speed preview started");

                                        activity.runOnUiThread(() -> {
                                            if (textureView != null)
                                                textureView.setAlpha(1f);
                                            if (blackPlaceholder != null && overlay != null)
                                                overlay.removeView(blackPlaceholder);
                                            OnnxPreChecking.startMonitoring(textureView, activity, backgroundHandler);
                                        });

                                    } catch (CameraAccessException e) {
                                        Log.e(TAG, "Failed to start high speed preview", e);
                                        tryLowerFpsFallback();
                                    }
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "High speed configuration failed");
                                tryLowerFpsFallback();
                            }
                        }, backgroundHandler);

            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "Exception during session setup", e);
                tryLowerFpsFallback();
            }
        }
    }

    public void tryLowerFpsFallback() {
        synchronized (cameraLock) {
            try {
                if (videoFrameRate >= 240) {
                    Log.w(TAG, "240fps failed ➡︎ trying 120fps");
                    videoFrameRate = 120;
                    startHighSpeedCaptureSession();
                } else if (videoFrameRate >= 120) {
                    Log.w(TAG, "120fps failed ➡︎ trying 60fps");
                    videoFrameRate = 60;
                    fallbackCallback.tryStandardSessionAgain();
                } else if (videoFrameRate >= 60) {
                    Log.w(TAG, "60fps failed ➡︎ trying 30fps");
                    videoFrameRate = 30;
                    fallbackCallback.tryStandardSessionAgain();
                } else {
                    rejectCallback.reject("All fallback attempts failed (fps & resolution)");
                    cleanupCallback.run();
                }
            } catch (Exception e) {
                rejectCallback.reject("Error during fallback: " + e.getMessage());
                cleanupCallback.run();
            }
        }
    }

    public String getCameraErrorMessage(int errorCode) {
        switch (errorCode) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "Camera in use";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "Too many cameras open";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "Camera disabled by policy";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "Fatal device error";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "Camera service error";
            default:
                return "Unknown camera error";
        }
    }

    // More methods like selectOptimalConfiguration(...) and
    // startStandardCaptureSession() can follow here.
}
