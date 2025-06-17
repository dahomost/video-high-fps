package com.daho.videohighfps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.graphics.Rect;

@CapacitorPlugin(name = "TpaCamera", permissions = {
        @Permission(strings = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
        }, alias = "camera")
})

public class TpaCameraPlugin extends Plugin {

    private static final String TAG = "TpaCamera";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private CameraManager cameraManager;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private TextureView textureView;
    private Surface previewSurface;
    private FrameLayout overlay;
    private ImageButton recordButton, pauseButton, stopButton, backButton;
    private TextView timerView;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private long startTime;
    private String videoPath; // Absolute file path,
    private PluginCall storedCall;
    private Size selectedSize;
    private String selectedCameraId;
    private int videoFrameRate;
    private long sizeLimit;

    private View blackPlaceholder;

    private final Handler timerHandler = new Handler();
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsed = SystemClock.elapsedRealtime() - startTime;
            int seconds = (int) (elapsed / 1000);
            int minutes = seconds / 60;
            seconds = seconds % 60;
            timerView.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 1000);
        }
    };

    @PluginMethod
    public void startRecording(PluginCall call) {
        Log.d(TAG, "-> startRecording(PluginCall call) is called . . . . . . . .");

        this.storedCall = call;
        this.cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            return;
        }

        Log.d(TAG, "  startRecording -> Permission granted...");

        // Read and log incoming options
        this.videoFrameRate = call.getInt("fps", 240);
        String resolution = call.getString("resolution", "fhd");
        this.sizeLimit = call.getLong("sizeLimit", 0L);

        Log.d(TAG, "[startRecording] Params:");
        Log.d(TAG, " - fps: " + videoFrameRate);
        Log.d(TAG, " - sizeLimit: " + sizeLimit);
        Log.d(TAG, " - resolution: " + resolution);

        try {
            // Automatically select best supported configuration
            this.selectedCameraId = getPreferredCameraId();
            selectOptimalConfiguration(resolution, videoFrameRate);

            // 🔧 Move preview setup to UI thread to prevent crash
            getActivity().runOnUiThread(() -> {
                try {
                    showCameraPreview(); // creates textureView, overlay

                    // Hide Ionic WebView manually
                    View webView = getBridge().getWebView();
                    if (webView != null) {
                        webView.setVisibility(View.GONE);
                        Log.d(TAG, "WebView hidden for full-screen native recording");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show camera preview", e);
                    storedCall.reject("Failed to show camera preview: " + e.getMessage());
                }
            });

            Log.d(TAG, "Initializing MediaRecorder with direct file path......");
            setupMediaRecorder(); // set outputFile, orientation, etc.

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            cleanupResources();
            call.reject("Failed to start recording: " + e.getMessage());

            // Restore Ionic WebView manually
            getActivity().runOnUiThread(() -> {
                try {
                    View webView = getBridge().getWebView();
                    if (webView != null) {
                        webView.setVisibility(View.VISIBLE);
                        Log.d(TAG, "WebView restored after error");
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to restore WebView", ex);
                }
            });
        }
    }

    private String getPreferredCameraId() throws CameraAccessException {
        String[] cameraIds = cameraManager.getCameraIdList();
        if (cameraIds.length == 0) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No cameras available");
        }

        // Log available cameras for debugging
        Log.d(TAG, "Available cameras...: " + Arrays.toString(cameraIds));

        // Prefer back camera
        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }

        // Fallback to first available camera
        Log.w(TAG, "No back camera found, using camera ID: " + cameraIds[0]);
        return cameraIds[0];
    }

    private void selectOptimalConfiguration(String resolution, int requestedFps) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (configMap == null) {
            throw new IllegalStateException("Cannot access camera configuration");
        }

        Size[] availableSizes = configMap.getOutputSizes(MediaRecorder.class);
        Range<Integer>[] highSpeedRanges;

        // Define preferred sizes based on input
        Size preferredSize;
        switch (resolution) {
            case "uhd":
            case "4k":
                preferredSize = new Size(3840, 2160);
                break;
            case "fhd":
            case "1080p":
                preferredSize = new Size(1920, 1080);
                break;
            case "hd":
            case "720p":
            default:
                preferredSize = new Size(1280, 720);
                break;
        }

        // Try to use high-speed mode (≥120fps)
        boolean useHighSpeed = requestedFps > 60;

        // Fallback logic
        selectedSize = null;
        videoFrameRate = 30;

        if (useHighSpeed) {
            try {
                highSpeedRanges = configMap.getHighSpeedVideoFpsRangesFor(preferredSize);
                for (Range<Integer> range : highSpeedRanges) {
                    if (range.getLower() <= requestedFps && range.getUpper() >= requestedFps) {
                        selectedSize = preferredSize;
                        videoFrameRate = requestedFps;
                        Log.d(TAG, "Using high-speed mode: " + selectedSize + " @" + videoFrameRate + "fps");
                        return;
                    }
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Requested size not valid for high-speed: " + preferredSize);
            }

            // Fallback to supported high-speed sizes
            for (Size size : configMap.getHighSpeedVideoSizes()) {
                highSpeedRanges = configMap.getHighSpeedVideoFpsRangesFor(size);
                for (Range<Integer> range : highSpeedRanges) {
                    int maxFps = range.getUpper();
                    if (maxFps >= 120) {
                        selectedSize = size;
                        videoFrameRate = Math.min(requestedFps, maxFps);
                        Log.w(TAG, "Fallback to high-speed: " + selectedSize + " @" + videoFrameRate + "fps");
                        return;
                    }
                }
            }

            Log.w(TAG, "No high-speed mode supported, falling back to standard recording.");
        }

        // Standard fallback mode (≤60fps)
        int bestArea = 0;
        for (Size size : availableSizes) {
            int area = size.getWidth() * size.getHeight();
            if (area > bestArea && size.getWidth() <= preferredSize.getWidth()) {
                selectedSize = size;
                bestArea = area;
            }
        }

        if (selectedSize == null) {
            selectedSize = availableSizes[0]; // last resort fallback
        }

        videoFrameRate = Math.min(requestedFps, 60); // cap to 60fps in standard mode
        Log.w(TAG, "Using standard mode fallback: " + selectedSize + " @" + videoFrameRate + "fps");
    }

    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    @PermissionCallback
    private void onCameraPermissionResult(PluginCall call) {
        Log.d(TAG, "onCameraPermissionResult -->  getPermissionState");
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            startRecording(call);
        } else {
            Log.e(TAG, "onCameraPermissionResult --> Surface setup failed");
            call.reject("Camera permission denied");
        }
    }

    private View blackOverlayView; // Class field

    private void showCameraPreview() {
        Log.d(TAG, "showCameraPreview -> cancelRecording() triggered");

        Activity activity = getActivity();
        if (activity == null) {
            storedCall.reject("Activity not available");
            return;
        }

        // 🔧 Black screen to prevent white flash
        blackOverlayView = new View(activity);
        blackOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        blackOverlayView.setBackgroundColor(0xFF000000); // Full black
        blackOverlayView.setAlpha(1f);

        FrameLayout root = activity.findViewById(android.R.id.content);
        root.addView(blackOverlayView);
        blackOverlayView.bringToFront(); // Force on top

        // 🔧 TextureView setup
        textureView = new TextureView(activity);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        textureView.setKeepScreenOn(true); // Prevent sleep

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                try {
                    surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                    previewSurface = new Surface(surface);
                    configureTransform(width, height);
                    openCamera(); // ⏩ Start camera (leads to preview)
                } catch (Exception e) {
                    Log.e(TAG, "Surface setup failed", e);
                    storedCall.reject("Surface setup failed: " + e.getMessage());
                    cleanupResources();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                configureTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });

        setupUI(); // Adds textureView and UI buttons
    }

    @SuppressLint("SetTextI18n")
    private void setupUI() {
        Activity activity = getActivity();
        if (activity == null) {
            storedCall.reject("Activity not available");
            return;
        }

        overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0xFF000000);

        // Black placeholder to prevent flash before TextureView is ready
        blackPlaceholder = new View(activity);
        blackPlaceholder.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        blackPlaceholder.setBackgroundColor(0xFF000000);
        overlay.addView(blackPlaceholder); // Add first

        // TextureView
        textureView.setAlpha(0f); // hidden initially
        textureView.setKeepScreenOn(true); // prevent screen from sleeping
        overlay.addView(textureView);

        // Timer
        timerView = new TextView(activity);
        timerView.setText("00:00");
        timerView.setTextSize(24);
        timerView.setTextColor(0xFFFFFFFF);
        timerView.setBackgroundColor(0xAA000000);
        timerView.setPadding(20, 10, 20, 10);
        FrameLayout.LayoutParams timerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        timerParams.setMargins(0, 50, 0, 0);
        timerView.setLayoutParams(timerParams);
        overlay.addView(timerView);

        // Buttons
        recordButton = createIconButton(R.drawable.start);
        pauseButton = createIconButton(R.drawable.pause);
        stopButton = createIconButton(R.drawable.stop);
        backButton = createIconButton(R.drawable.back);

        LinearLayout buttonsLayout = new LinearLayout(activity);
        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonsLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams buttonsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonsParams.setMargins(0, 0, 0, 60);
        buttonsLayout.setLayoutParams(buttonsParams);

        int buttonSize = 160;
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        buttonParams.setMargins(30, 0, 30, 0);

        recordButton.setLayoutParams(buttonParams);
        backButton.setLayoutParams(buttonParams);
        pauseButton.setLayoutParams(buttonParams);
        stopButton.setLayoutParams(buttonParams);

        buttonsLayout.addView(backButton);
        buttonsLayout.addView(recordButton);
        buttonsLayout.addView(pauseButton);
        buttonsLayout.addView(stopButton);

        pauseButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);

        overlay.addView(buttonsLayout);

        activity.runOnUiThread(() -> {
            FrameLayout root = activity.findViewById(android.R.id.content);
            root.addView(overlay);
        });

        setupButtonListeners();
    }

    private ImageButton createIconButton(@DrawableRes int drawableId) {
        Activity activity = getActivity();
        if (activity == null)
            return null;

        ImageButton button = new ImageButton(activity);
        button.setImageResource(drawableId);

        // Transparent background and centered scaling for SVG
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setAdjustViewBounds(true); // maintain aspect ratio

        // Set button size to 250x250 pixels
        int size = 250;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(20, 0, 20, 0); // spacing between buttons
        button.setLayoutParams(params);

        // Explicitly enforce size (in case layout doesn't honor params)
        button.setMinimumWidth(size);
        button.setMinimumHeight(size);
        button.setMaxWidth(size);
        button.setMaxHeight(size);
        button.setPadding(0, 0, 0, 0); // no padding

        return button;
    }

    private void setupButtonListeners() {
        if (recordButton != null) {
            recordButton.setOnClickListener(v -> {
                if (!isRecording)
                    startRecordingInternal();
            });
        }

        if (stopButton != null) {
            stopButton.setOnClickListener(v -> {
                if (isRecording)
                    stopRecording();
                else
                    cancelRecording();
            });
        }

        if (pauseButton != null) {
            pauseButton.setOnClickListener(v -> {
                Log.d(TAG, "Pause/Resume clicked");

                if (isRecording && !isPaused) {
                    // Pausing
                    pauseRecording(); // Call your pauseRecording method
                    if (stopButton != null)
                        stopButton.setVisibility(View.GONE);
                    pauseButton.setImageResource(R.drawable.start); // Resume icon
                } else if (isRecording && isPaused) {
                    // Resuming
                    resumeRecording(); // Call your resumeRecording method
                    if (stopButton != null)
                        stopButton.setVisibility(View.VISIBLE);
                    pauseButton.setImageResource(R.drawable.pause); // Pause icon
                }
            });
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> cancelRecording());
        }
    }

    private void openCamera() {
        Activity activity = getActivity();
        if (activity == null || ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            storedCall.reject("Camera permission not granted");
            cleanupResources();
            return;
        }

        try {
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        if (videoFrameRate >= 120) {
                            startHighSpeedCaptureSession();
                        } else {
                            startStandardCaptureSession();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start capture session", e);
                        storedCall.reject("Failed to start capture session: " + e.getMessage());
                        cleanupResources();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    cleanupResources();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    storedCall.reject("Camera error: " + error);
                    cleanupResources();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            storedCall.reject("Camera access failed: " + e.getMessage());
            cleanupResources();
        }
    }

    private void startHighSpeedCaptureSession() throws Exception {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (configMap == null || configMap.getHighSpeedVideoSizes() == null) {
            Log.w(TAG, "High-speed not supported, falling back to standard");
            startStandardCaptureSession();
            return;
        }

        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null)
            throw new IllegalStateException("Surface texture not available");

        surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
        previewSurface = new Surface(surfaceTexture);

        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(previewSurface);
        surfaces.add(recorderSurface);

        cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                try {
                    CameraConstrainedHighSpeedCaptureSession hsSession = (CameraConstrainedHighSpeedCaptureSession) session;
                    CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(previewSurface);
                    builder.addTarget(recorderSurface);
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            new Range<>(videoFrameRate, videoFrameRate));

                    Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    if (sensorRect == null) {
                        Log.w(TAG, "Sensor info active array size not available, using default");
                        sensorRect = new Rect(0, 0, selectedSize.getWidth(), selectedSize.getHeight());
                    }
                    builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect);

                    hsSession.setRepeatingBurst(hsSession.createHighSpeedRequestList(builder.build()), null,
                            backgroundHandler);

                    textureView.post(() -> {
                        configureTransform(textureView.getWidth(), textureView.getHeight());

                        // Fade in the preview
                        textureView.animate().alpha(1f).setDuration(300).start();

                        // Remove black placeholder from overlay
                        if (blackPlaceholder != null) {
                            blackPlaceholder.animate()
                                    .alpha(0f)
                                    .setDuration(200)
                                    .withEndAction(() -> overlay.removeView(blackPlaceholder))
                                    .start();
                        }

                        // Remove full-screen black overlay
                        if (blackOverlayView != null) {
                            ViewGroup root = (ViewGroup) getActivity().findViewById(android.R.id.content);
                            root.removeView(blackOverlayView);
                            blackOverlayView = null;
                        }
                    });

                    getActivity().runOnUiThread(() -> {
                        recordButton.setVisibility(View.VISIBLE);
                        Toast.makeText(getContext(), "Ready: " + videoFrameRate + "fps " +
                                selectedSize.getWidth() + "x" + selectedSize.getHeight(), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Failed to start high-speed preview", e);
                    storedCall.reject("Failed to start preview: " + e.getMessage());
                    cleanupResources();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Log.e(TAG, "High-speed session configuration failed");
                storedCall.reject("High-speed configuration failed");
                cleanupResources();
            }
        }, backgroundHandler);
    }

    private void startStandardCaptureSession() throws Exception {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            throw new IllegalStateException("Surface texture not available");
        }
        surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
        previewSurface = new Surface(surfaceTexture);

        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(previewSurface);
        surfaces.add(recorderSurface);

        cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                try {
                    CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(previewSurface);
                    builder.addTarget(recorderSurface);
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            new Range<>(videoFrameRate, videoFrameRate));

                    session.setRepeatingRequest(builder.build(), null, backgroundHandler);

                    textureView.post(() -> {
                        configureTransform(textureView.getWidth(), textureView.getHeight());

                        // Fade in the preview
                        textureView.animate().alpha(1f).setDuration(300).start();

                        // Remove black placeholder from overlay
                        if (blackPlaceholder != null) {
                            blackPlaceholder.animate()
                                    .alpha(0f)
                                    .setDuration(200)
                                    .withEndAction(() -> overlay.removeView(blackPlaceholder))
                                    .start();
                        }

                        // Remove full-screen black overlay
                        if (blackOverlayView != null) {
                            ViewGroup root = (ViewGroup) getActivity().findViewById(android.R.id.content);
                            root.removeView(blackOverlayView);
                            blackOverlayView = null;
                        }
                    });

                    getActivity().runOnUiThread(() -> {
                        recordButton.setVisibility(View.VISIBLE);
                        Toast.makeText(getContext(), "Ready: " + videoFrameRate + "fps " +
                                selectedSize.getWidth() + "x" + selectedSize.getHeight(), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Failed to start standard preview", e);
                    storedCall.reject("Failed to start preview: " + e.getMessage());
                    cleanupResources();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Log.e(TAG, "Standard session configuration failed");
                storedCall.reject("Standard configuration failed");
                cleanupResources();
            }
        }, backgroundHandler);
    }

    private int calculateBitrate(Size resolution, int fps) {
        int pixels = resolution.getWidth() * resolution.getHeight();
        if (pixels >= 3840 * 2160)
            return fps >= 120 ? 40_000_000 : 30_000_000;
        if (pixels >= 1920 * 1080)
            return fps >= 120 ? 20_000_000 : 15_000_000;
        return fps >= 120 ? 12_000_000 : 8_000_000;
    }

    private void cleanupResources() {
        try {
            timerHandler.removeCallbacks(timerRunnable);
            isRecording = false;
            isPaused = false;
            startTime = 0;

            if (mediaRecorder != null) {
                try {
                    mediaRecorder.reset();
                    mediaRecorder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing media recorder", e);
                }
                mediaRecorder = null;
            }

            if (captureSession != null) {
                try {
                    captureSession.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing capture session", e);
                }
                captureSession = null;
            }

            if (cameraDevice != null) {
                try {
                    cameraDevice.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing camera device", e);
                }
                cameraDevice = null;
            }

            if (previewSurface != null) {
                try {
                    previewSurface.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing preview surface", e);
                }
                previewSurface = null;
            }

            Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        FrameLayout root = activity.findViewById(android.R.id.content);
                        if (overlay != null) {
                            root.removeView(overlay);
                            overlay = null;
                            Log.d(TAG, "Overlay removed");
                        }

                        // -> Restore WebView
                        View webView = getBridge().getWebView();
                        if (webView != null) {
                            webView.setVisibility(View.VISIBLE);
                            Log.d(TAG, "WebView restored after recording");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error restoring WebView", e);
                    }
                });
            }

            if (backgroundThread != null) {
                backgroundThread.quitSafely();
                try {
                    backgroundThread.join();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Error joining background thread", e);
                }
                backgroundThread = null;
                backgroundHandler = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (textureView == null || selectedSize == null || activity == null) {
            return;
        }

        Matrix matrix = new Matrix();
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, selectedSize.getHeight(), selectedSize.getWidth()); // width <-> height due
                                                                                               // to rotation
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);

        // Scale only to match the width, keep full aspect ratio
        float scaleX = (float) viewWidth / selectedSize.getHeight(); // Use rotated width
        matrix.postScale(scaleX, scaleX, centerX, centerY); // Same scale for X and Y

        // Rotate and fix upside-down
        matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        matrix.postScale(1, -1, centerX, centerY); // Fix vertical flip

        textureView.setTransform(matrix);
    }

    private int getRotationDegrees(int sensorOrientation, int displayRotation) {
        int rotation;
        switch (displayRotation) {
            case Surface.ROTATION_0:
                rotation = 0;
                break;
            case Surface.ROTATION_90:
                rotation = 90;
                break;
            case Surface.ROTATION_180:
                rotation = 180;
                break;
            case Surface.ROTATION_270:
                rotation = 270;
                break;
            default:
                rotation = 0;
        }

        return (sensorOrientation - rotation + 360) % 360;

    }

    private void setupMediaRecorder() throws IOException {
        Log.d(TAG, "Initializing MediaRecorder with direct file path......");

        // Step 1: Prepare file path

        String fileName = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        File videosDir = new File(getContext().getExternalFilesDir(null), "tpa-videos");

        if (!videosDir.exists() && !videosDir.mkdirs()) {
            throw new IOException("Failed to create videos directory: " + videosDir.getAbsolutePath());
        }

        File outputFile = new File(videosDir, fileName);
        videoPath = outputFile.getAbsolutePath(); // this is now a direct path

        Log.d(TAG, "Saving to: " + videoPath);

        // Step 2: Configure MediaRecorder
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(videoPath); // use file path
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        int bitrate = calculateBitrate(selectedSize, videoFrameRate);
        mediaRecorder.setVideoEncodingBitRate(bitrate);
        mediaRecorder.setVideoFrameRate(videoFrameRate);
        mediaRecorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());

        Activity activity = getActivity();
        if (activity != null) {
            Display display = activity.getWindowManager().getDefaultDisplay();
            int rotation = display.getRotation();
            int orientation = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) ? 90 : 0;
            mediaRecorder.setOrientationHint(orientation);
            Log.d(TAG, "Orientation hint set to " + orientation + "°");
        }

        if (sizeLimit > 0) {
            mediaRecorder.setMaxFileSize(sizeLimit);
            Log.d(TAG, "Max file size set: " + sizeLimit + " bytes");
        }

        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                Log.w(TAG, "⚠ Max file size reached");
            }
        });

        mediaRecorder.prepare();
        Log.d(TAG, "MediaRecorder prepared (file output mode)");
    }

    private void startRecordingInternal() {
        try {

            if (mediaRecorder == null) {
                Log.e(TAG, "MediaRecorder is null, cannot start recording");
                storedCall.reject("MediaRecorder not initialized");
                cleanupResources();
                return;
            }

            mediaRecorder.start();
            isRecording = true;
            isPaused = false;
            startTime = SystemClock.elapsedRealtime();
            timerHandler.post(timerRunnable);

            getActivity().runOnUiThread(() -> {
                recordButton.setVisibility(View.GONE);
                pauseButton.setVisibility(View.VISIBLE);
                stopButton.setVisibility(View.VISIBLE);
                backButton.setVisibility(View.GONE);
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            storedCall.reject("Failed to start recording: " + e.getMessage());
            cleanupResources();
        }
    }

    private void stopRecording() {
        Log.d(TAG, "🔴 startRecordingInternal() called");
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }

            long durationMillis = SystemClock.elapsedRealtime() - startTime;
            float durationSec = durationMillis / 1000f;

            File file = new File(videoPath);
            long fileSizeBytes = file.exists() ? file.length() : 0;
            float fileSizeMB = fileSizeBytes / (1024f * 1024f);

            // Delete if file is too short or has 0 bytes
            if (durationSec < 0.5f || fileSizeBytes == 0) {
                Log.w(TAG, "Recording too short or file invalid, deleting: " + videoPath);
                if (file.exists()) {
                    boolean deleted = file.delete();
                    Log.w(TAG, "Deleted file: " + deleted);
                }
                storedCall.reject("Recording too short or failed");
                return;
            }

            // Log file size
            Log.d(TAG, String.format(Locale.US, "Video saved: %.2f MB (%d bytes)", fileSizeMB, fileSizeBytes));

            JSObject result = new JSObject();
            result.put("videoPath", videoPath);
            result.put("frameRate", videoFrameRate);
            result.put("resolution", selectedSize.getWidth() + "x" + selectedSize.getHeight());
            result.put("duration", durationSec);
            result.put("sizeLimit", sizeLimit);
            result.put("fileSizeMB", fileSizeMB);

            Log.d(TAG, "[stopRecording] Result JSON:\n" + result.toString(2));
            storedCall.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
            storedCall.reject("Failed to stop recording: " + e.getMessage());
        } finally {
            cleanupResources();
        }
    }

    private void pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder.pause();
                isPaused = true;
                timerHandler.removeCallbacks(timerRunnable);

                getActivity().runOnUiThread(() -> {
                    pauseButton.setBackgroundResource(R.drawable.start); // Set image icon
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to pause recording", e);
            }
        } else {
            stopRecording();
        }
    }

    private void resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder.resume();
                isPaused = false;
                timerHandler.post(timerRunnable);

                getActivity().runOnUiThread(() -> {
                    pauseButton.setBackgroundResource(R.drawable.pause); // Set image icon
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to resume recording", e);
            }
        } else {
            startRecordingInternal();
        }
    }

    private void cancelRecording() {
        Log.d(TAG, "x - cancelRecording() triggered");
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder.stop();
            }
            storedCall.reject("Recording canceled by user");
        } catch (Exception e) {
            Log.e(TAG, "Error canceling recording", e);
            storedCall.reject("Error canceling recording: " + e.getMessage());
        } finally {
            cleanupResources();
        }
    }
}