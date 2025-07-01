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
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
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
import android.view.WindowInsets;
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

@CapacitorPlugin(name = "TpaCamera", permissions = {
        @Permission(strings = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
        }, alias = "camera")
})

public class TpaCameraPlugin extends Plugin {

    private static final String TAG = "TpaCamera --=>";
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
    private final Object cameraLock = new Object();

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
        Log.d(TAG, "startRecording(PluginCall call) is called");

        this.storedCall = call;
        this.cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            return;
        }

        Log.d(TAG, "startRecording -> Permission granted...");

        // Read parameters
        Integer fpsOpt = call.getInt("fps");
        this.videoFrameRate = (fpsOpt != null) ? fpsOpt : 240;

        String resOpt = call.getString("resolution");
        String resolution = (resOpt != null) ? resOpt : "1080p";

        Long sizeOpt = call.getLong("sizeLimit");
        this.sizeLimit = (sizeOpt != null) ? sizeOpt : 0L;

        Log.d(TAG, "start Recording Params:");
        Log.d(TAG, " --> fps: " + videoFrameRate);
        Log.d(TAG, " --> sizeLimit: " + sizeLimit);
        Log.d(TAG, " --> resolution: " + resolution);
        Log.d(TAG, " ==> Device Info:");
        Log.d(TAG, "   - Manufacturer: " + Build.MANUFACTURER);
        Log.d(TAG, "   - Model       : " + Build.MODEL);
        Log.d(TAG, "   - Android API : " + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")");

        try {
            // Full reset before reusing
            cleanupResources();
            stopBackgroundThread();

            // Start fresh
            startBackgroundThread();
            cameraDevice = null;
            captureSession = null;
            mediaRecorder = null;
            isRecording = false;
            isPaused = false;

            this.selectedCameraId = getPreferredCameraId();
            selectOptimalConfiguration(resolution, videoFrameRate);

            getActivity().runOnUiThread(() -> {
                try {
                    showCameraPreview();

                    // Hide WebView for native fullscreen
                    View webView = getBridge().getWebView();
                    if (webView != null) {
                        webView.setVisibility(View.GONE);
                        Log.d(TAG, "WebView hidden for native camera mode");
                    }
                } catch (Exception e) {
                    rejectIfPossible(e.getMessage());
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to startRecording()", e);
            cleanupResources();
            stopBackgroundThread();
            call.reject("Failed to start recording: " + e.getMessage());

            // Try restoring WebView
            getActivity().runOnUiThread(() -> {
                try {
                    View webView = getBridge().getWebView();
                    if (webView != null) {
                        webView.setVisibility(View.VISIBLE);
                        fadeTo(webView, 1f, 200);
                        Log.d(TAG, "WebView restored after error in startRecording");
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to restore WebView after error", ex);
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

        // Log full capability matrix
        Log.d(TAG, "Supported High-Speed Video Sizes and FPS Ranges:");
        for (Size size : configMap.getHighSpeedVideoSizes()) {
            try {
                Range<Integer>[] ranges = configMap.getHighSpeedVideoFpsRangesFor(size);
                for (Range<Integer> r : ranges) {
                    Log.d(TAG, size.getWidth() + "x" + size.getHeight() + " @ " + r.getLower() + "–"
                            + r.getUpper() + " fps");
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, size + " (not valid for high-speed)");
            }
        }

        Size[] availableSizes = configMap.getOutputSizes(MediaRecorder.class);
        selectedSize = null;
        videoFrameRate = 30;

        List<Integer> fallbackFpsList = Arrays.asList(240, 120, 60, 30);
        List<Size> resolutionPriority = Arrays.asList(
                new Size(1920, 1080), // Full HD
                new Size(1280, 720) // HD
        );

        // === Fallback Decision Trace ===
        for (int targetFps : fallbackFpsList) {
            for (Size size : resolutionPriority) {
                Log.d(TAG, "Trying Fallback: " + size.getWidth() + "x" + size.getHeight() + " @ " + targetFps + "fps");

                try {
                    List<Size> supportedHighSpeedSizes = Arrays.asList(configMap.getHighSpeedVideoSizes());
                    if (!supportedHighSpeedSizes.contains(size)) {
                        Log.d(TAG, "Not in supportedHighSpeedSizes");
                        continue;
                    }

                    Range<Integer>[] ranges = configMap.getHighSpeedVideoFpsRangesFor(size);
                    if (ranges == null) {
                        Log.d(TAG, "No FPS range info");
                        continue;
                    }

                    for (Range<Integer> range : ranges) {
                        if (range.getLower() <= targetFps && range.getUpper() >= targetFps) {
                            selectedSize = size;
                            videoFrameRate = targetFps;
                            Log.d(TAG, "Selected high-speed config: " + selectedSize + " @" + videoFrameRate + "fps");
                            return;
                        }
                    }

                    Log.d(TAG, "FPS " + targetFps + " not in supported range for this size");

                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "IllegalArgumentException for size " + size + ": " + e.getMessage());
                }
            }
        }

        Log.w(TAG, "No high-speed config matched. Falling back to standard mode.");

        Size preferredMax = resolution.equals("4k") ? new Size(3840, 2160)
                : resolution.equals("1080p") ? new Size(1920, 1080)
                        : new Size(1280, 720);

        Size bestStandardSize = null;
        int bestArea = 0;
        for (Size size : availableSizes) {
            int area = size.getWidth() * size.getHeight();
            if (area > bestArea && size.getWidth() <= preferredMax.getWidth()) {
                bestStandardSize = size;
                bestArea = area;
            }
        }

        if (bestStandardSize != null) {
            selectedSize = bestStandardSize;
        } else if (availableSizes.length > 0) {
            selectedSize = availableSizes[0];
        } else {
            throw new IllegalStateException("No valid MediaRecorder sizes available");
        }

        videoFrameRate = Math.min(requestedFps, 30);
        Log.d(TAG, "Selected standard config: " + selectedSize + " @" + videoFrameRate + "fps");
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
            rejectIfPossible("Activity not available");
            return;
        }

        // Black screen to prevent white flash
        blackOverlayView = new View(activity);
        blackOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        blackOverlayView.setBackgroundColor(0xFF000000); // Full black
        blackOverlayView.setAlpha(1f);

        FrameLayout root = activity.findViewById(android.R.id.content);
        root.addView(blackOverlayView);
        blackOverlayView.bringToFront(); // Force on top

        // TextureView setup
        textureView = new TextureView(activity);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        textureView.setKeepScreenOn(true); // Prevent sleep

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (selectedSize != null) {
                    surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                    previewSurface = new Surface(surface);
                    configureTransform(width, height);
                    openCamera();
                } else {
                    rejectIfPossible("Camera preview setup failed: no resolution selected");
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

    /**
     * Density-independent-pixels → physical pixels
     */
    private int dpToPx(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @SuppressLint("SetTextI18n")
    private void setupUI() {
        Activity activity = getActivity();
        if (activity == null) {
            rejectIfPossible("Activity not available");
            return;
        }

        overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(0xFF000000);

        // ── Black placeholder ─
        blackPlaceholder = new View(activity);
        blackPlaceholder.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        blackPlaceholder.setBackgroundColor(0xFF000000);
        overlay.addView(blackPlaceholder);

        // ── TextureView ─
        textureView.setAlpha(0f);
        textureView.setKeepScreenOn(true);
        overlay.addView(textureView);

        // ── Timer label ─
        timerView = new TextView(activity);
        timerView.setText("00:00");
        timerView.setTextSize(12);
        timerView.setTextColor(0xFFFFFFFF);
        timerView.setPadding(dpToPx(activity, 12), dpToPx(activity, 6),
                dpToPx(activity, 12), dpToPx(activity, 6));

        GradientDrawable timerBg = new GradientDrawable();
        timerBg.setColor(0xAA000000); // semi-transparent black
        timerBg.setCornerRadius(dpToPx(activity, 8)); // rounded corners
        timerBg.setStroke(dpToPx(activity, 1), Color.GRAY); // gray outline
        timerView.setBackground(timerBg);

        FrameLayout.LayoutParams timerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        timerParams.setMargins(0, dpToPx(activity, 80), 0, 0);
        timerView.setLayoutParams(timerParams);
        overlay.addView(timerView);

        // ── Buttons ─
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
        buttonsParams.setMargins(0, 0, 0, dpToPx(activity, 48));
        buttonsLayout.setLayoutParams(buttonsParams);

        int buttonSizeDp = 48;
        int spacingDp = 12;

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                dpToPx(activity, buttonSizeDp),
                dpToPx(activity, buttonSizeDp));
        btnLp.setMargins(dpToPx(activity, spacingDp), 0,
                dpToPx(activity, spacingDp), 0);

        recordButton.setLayoutParams(btnLp);
        backButton.setLayoutParams(btnLp);
        pauseButton.setLayoutParams(btnLp);
        stopButton.setLayoutParams(btnLp);

        buttonsLayout.addView(backButton);
        buttonsLayout.addView(recordButton);
        buttonsLayout.addView(pauseButton);
        buttonsLayout.addView(stopButton);

        pauseButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);

        buttonsLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottomInset = dpToPx(activity, 48);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bottomInset += insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                bottomInset += insets.getSystemWindowInsetBottom();
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) buttonsLayout.getLayoutParams();
            lp.setMargins(0, 0, 0, bottomInset);
            buttonsLayout.setLayoutParams(lp);
            return insets;
        });

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
                    pauseRecording(); // Call pauseRecording method
                    if (stopButton != null)
                        stopButton.setVisibility(View.GONE);
                    pauseButton.setImageResource(R.drawable.start); // Resume icon
                } else if (isRecording) {
                    // Resuming
                    resumeRecording(); // Call resumeRecording method
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
            rejectIfPossible("Camera permission not granted");
            cleanupResources();
            return;
        }

        if (selectedCameraId == null) {
            Log.e(TAG, "selectedCameraId is null");
            rejectIfPossible("Camera ID not selected");
            cleanupResources();
            return;
        }

        try {
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    synchronized (cameraLock) {
                        Log.d(TAG, "onOpened() -> Assigning cameraDevice...");
                        cameraDevice = camera;
                    }

                    backgroundHandler.postDelayed(() -> {
                        try {
                            synchronized (cameraLock) {
                                if (cameraDevice == null) {
                                    rejectIfPossible("Camera disconnected before session start");
                                    return;
                                }

                                if (videoFrameRate >= 120) {
                                    startHighSpeedCaptureSession();
                                } else {
                                    startStandardCaptureSession();
                                }
                            }
                        } catch (Exception e) {
                            rejectIfPossible(e.getMessage());
                            getActivity().runOnUiThread(() -> cleanupResources());
                        }
                    }, 400);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    getActivity().runOnUiThread(() -> cleanupResources());
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    String fullError = getCameraErrorMessage(error);
                    rejectIfPossible(fullError);
                    getActivity().runOnUiThread(() -> cleanupResources());
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            rejectIfPossible("Camera access failed: " + e.getMessage());
            cleanupResources();
        }
    }

    private void startHighSpeedCaptureSession() {
        try {
            if (cameraDevice == null) {
                rejectIfPossible("cameraDevice is null – aborting high-speed session setup");
                return;
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
            StreamConfigurationMap configMap = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap == null || configMap.getHighSpeedVideoSizes() == null) {
                Log.w(TAG, "High-speed not supported, falling back to standard");
                tryStandardSessionAgain();
                return;
            }

            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                throw new IllegalStateException("Surface texture not available");
            }

            int previewWidth = 640;
            int previewHeight = 360;
            surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight);
            previewSurface = new Surface(surfaceTexture);

            safeReleaseMediaRecorder();

            // Setup MediaRecorder After surface are ready
            setupMediaRecorder();
            Surface recorderSurface = mediaRecorder.getSurface();

            List<Surface> surfaces = Arrays.asList(previewSurface, recorderSurface);

            cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        CameraConstrainedHighSpeedCaptureSession hsSession = (CameraConstrainedHighSpeedCaptureSession) session;

                        CaptureRequest.Builder builder = cameraDevice
                                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(previewSurface);
                        builder.addTarget(recorderSurface);
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                        // ✅ Relax AE target FPS range
                        Range<Integer> fpsRange = new Range<>(videoFrameRate, videoFrameRate + 15);
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                        Log.d(TAG, "Using relaxed FPS range: " + fpsRange);

                        List<CaptureRequest> burst = hsSession.createHighSpeedRequestList(builder.build());
                        hsSession.setRepeatingBurst(burst, null, backgroundHandler);

                        captureSession = session;
                        Log.d(TAG, "Live preview started at " + videoFrameRate + "fps (" + selectedSize.getWidth()
                                + "x" + selectedSize.getHeight() + ")");
                        onPreviewSuccess(); // Fade in

                    } catch (Exception e) {
                        Log.e(TAG, "High-speed burst failed. Trying lower FPS fallback.", e);
                        tryLowerFpsFallback();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "High-speed session configure failed. Trying lower FPS fallback.");
                    tryLowerFpsFallback();
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start high-speed capture session", e);
            tryLowerFpsFallback();
        }
    }

    private void tryLowerFpsFallback() {
        getActivity().runOnUiThread(() -> {
            try {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
                StreamConfigurationMap configMap = characteristics
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (configMap == null) {
                    rejectIfPossible("❌ Cannot access camera configuration.");
                    cleanupResources(); // ❗Only clean up if we abort
                    return;
                }

                List<Size> highSpeedSizes = Arrays.asList(configMap.getHighSpeedVideoSizes());

                // === Resolution Fallback ===
                if (selectedSize != null && selectedSize.getWidth() > 720) {
                    Size fallbackSize = new Size(720, 480);
                    if (highSpeedSizes.contains(fallbackSize)) {
                        Log.w(TAG, "Resolution fallback: trying 720x480 @ " + videoFrameRate + "fps");
                        selectedSize = fallbackSize;
                        tryHighSpeedAgain();
                        return;
                    } else {
                        Log.w(TAG, "720x480 not supported for high-speed. Skipping resolution fallback.");
                    }
                }

                // === FPS Fallback ===
                if (videoFrameRate >= 240) {
                    Log.w(TAG, "240fps failed → trying 120fps...");
                    videoFrameRate = 120;
                    tryHighSpeedAgain();
                } else if (videoFrameRate >= 120) {
                    Log.w(TAG, "120fps failed → trying 60fps...");
                    videoFrameRate = 60;
                    tryStandardSessionAgain();
                } else if (videoFrameRate >= 60) {
                    Log.w(TAG, "60fps failed → trying 30fps...");
                    videoFrameRate = 30;
                    tryStandardSessionAgain();
                } else {
                    rejectIfPossible("All fallback attempts failed (fps & resolution)");
                    cleanupResources();
                }

            } catch (CameraAccessException e) {
                rejectIfPossible("Camera access error during fallback: " + e.getMessage());
                cleanupResources();
            }
        });
    }

    private void tryHighSpeedAgain() {
        try {
            startHighSpeedCaptureSession();
        } catch (Exception e) {
            Log.w(TAG, "High-speed retry failed. Trying standard...", e);
            tryStandardSessionAgain();
        }
    }

    private void tryStandardSessionAgain() {
        try {
            startStandardCaptureSession();
            Log.d(TAG, "Final fallback: standard session @ " + videoFrameRate + "fps");
        } catch (Exception e) {
            rejectIfPossible("❌ Standard fallback failed: " + e.getMessage());
        }
    }

    private void onPreviewSuccess() {
        Log.d(TAG, "Preview started successfully");

        getActivity().runOnUiThread(() -> {
            if (textureView != null) {
                textureView.setAlpha(1f); // Remove fade-in effect if any
            }

            // Show only initial buttons
            if (recordButton != null)
                recordButton.setVisibility(View.VISIBLE);
            if (pauseButton != null)
                pauseButton.setVisibility(View.GONE);
            if (stopButton != null)
                stopButton.setVisibility(View.GONE);
            if (backButton != null)
                backButton.setVisibility(View.VISIBLE);
        });
    }

    private void startStandardCaptureSession() throws Exception {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            throw new IllegalStateException("Surface texture not available");
        }
        surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
        previewSurface = new Surface(surfaceTexture);

        // Reset old MediaRecorder if it exists
        safeReleaseMediaRecorder();

        // setup MediaRecorder after surfaces are ready
        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();
        if (recorderSurface == null) {
            throw new IllegalStateException("Recorder surface is null after prepare()");
        }

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
                        fadeTo(textureView, 1f, 300); // Fade in

                        // Remove black placeholder from overlay
                        if (blackPlaceholder != null) {
                            blackPlaceholder.animate()
                                    .alpha(0f)
                                    .setDuration(200)
                                    .withEndAction(() -> {
                                        try {
                                            if (overlay != null && blackPlaceholder != null
                                                    && blackPlaceholder.getParent() == overlay) {
                                                overlay.removeView(blackPlaceholder);
                                            }
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to remove blackPlaceholder", e);
                                        }
                                    })
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
                        Toast.makeText(getContext(), "Ready: " + videoFrameRate + "fps " +
                                selectedSize.getWidth() + "x" + selectedSize.getHeight(), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    rejectIfPossible("Failed to start preview: " + e.getMessage());
                    getActivity().runOnUiThread(() -> cleanupResources());
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                rejectIfPossible("Standard configuration failed");
                getActivity().runOnUiThread(() -> cleanupResources());
            }
        }, backgroundHandler);
    }

    private int calculateBitrate(Size resolution, int fps) {
        int pixels = resolution.getWidth() * resolution.getHeight();
        int base = (int) (pixels * fps * 0.07f);
        return Math.min(base, 50_000_000);
    }

    private void cleanupResources() {
        timerHandler.removeCallbacks(timerRunnable);
        Log.d(TAG, "cleanupResources() called");
        try {
            if (captureSession != null) {
                try {
                    captureSession.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close captureSession", e);
                }
                captureSession = null;
            }

            if (cameraDevice != null) {
                try {
                    cameraDevice.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close cameraDevice", e);
                }
                cameraDevice = null;
            }

            safeReleaseMediaRecorder();

            if (previewSurface != null) {
                try {
                    previewSurface.release();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to release previewSurface", e);
                }
                previewSurface = null;
            }

            // Ensure UI view removal is done on UI thread
            getActivity().runOnUiThread(() -> {
                try {
                    if (blackPlaceholder != null && overlay != null) {
                        if (blackPlaceholder.getParent() == overlay) {
                            overlay.removeView(blackPlaceholder);
                        }
                    }
                    blackPlaceholder = null;

                    if (blackOverlayView != null) {
                        ViewGroup root = (ViewGroup) getActivity().findViewById(android.R.id.content);
                        if (blackOverlayView.getParent() == root) {
                            root.removeView(blackOverlayView);
                        }
                    }
                    blackOverlayView = null;

                    if (overlay != null) {
                        ViewGroup root = (ViewGroup) getActivity().findViewById(android.R.id.content);
                        if (overlay.getParent() == root) {
                            root.removeView(overlay);
                        }
                    }
                    overlay = null;

                    if (textureView != null && textureView.getAlpha() > 0f) {
                        fadeTo(textureView, 0f, 100); // Fade out camera preview
                    }

                    if (bridge != null && bridge.getWebView() != null) {
                        View webView = bridge.getWebView();
                        if (webView.getVisibility() != View.VISIBLE) {
                            webView.setVisibility(View.VISIBLE);
                            fadeTo(webView, 1f, 200); // Smooth fade in
                        }
                    }

                } catch (Exception e) {
                    Log.w(TAG, "Error during UI cleanup", e);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Unhandled error during cleanupResources..", e);
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
                Log.w(TAG, "Max file size reached");
            }
        });

        mediaRecorder.prepare();
        Log.d(TAG, "MediaRecorder prepared (file output mode)");
    }

    private void startRecordingInternal() {
        Log.d(TAG, "startRecordingInternal() called");

        if (isRecording) {
            Log.w(TAG, "startRecordingInternal: already recording, skipping");
            return;
        }

        if (mediaRecorder == null) {
            Log.e(TAG, "startRecordingInternal: MediaRecorder is null");
            rejectIfPossible("MediaRecorder is null, cannot start recording");
            getActivity().runOnUiThread(this::cleanupResources);
            return;
        }

        try {
            mediaRecorder.start();
            SystemClock.sleep(50); // ensure encoder is ready

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

            Log.d(TAG, "Recording started successfully");

        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to start MediaRecorder", e);
            rejectIfPossible("Failed to start recording: MediaRecorder error");
            getActivity().runOnUiThread(this::cleanupResources);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in startRecordingInternal", e);
            rejectIfPossible("Failed to start recording: " + e.getMessage());
            getActivity().runOnUiThread(this::cleanupResources);
        }
    }

    private void stopRecording() {
        Log.d(TAG, "🔴 stopRecording() method called");
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

                rejectIfPossible("Recording too short or failed");
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
            if (storedCall != null) {
                storedCall.resolve(result);
                storedCall = null;
            }

        } catch (Exception e) {
            rejectIfPossible("Failed to stop recording: " + e.getMessage());
        } finally {
            getActivity().runOnUiThread(this::cleanupResources);
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
            // Only attempt stop if recording actually started
            if (isRecording && mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    Log.d(TAG, "MediaRecorder stopped safely on cancel");
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaRecorder.stop() failed - not in recording state", e);
                }
            }

            rejectIfPossible("Recording canceled by the user, this is ok.");

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error canceling recording", e);
            rejectIfPossible("Error canceling recording: " + e.getMessage());
        } finally {
            isRecording = false;
            isPaused = false;
            storedCall = null;

            stopBackgroundThread(); // shut down camera thread

            getActivity().runOnUiThread(() -> {
                // UI-safe cleanup
                cleanupResources();

                // Bring back WebView
                if (bridge != null && bridge.getWebView() != null) {
                    View webView = bridge.getWebView();
                    webView.setVisibility(View.VISIBLE);
                    fadeTo(webView, 1f, 200);
                    Log.d(TAG, "WebView restored after cancel");
                }
            });
        }
    }

    private void safeReleaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Failed to release MediaRecorder", e);
            } finally {
                mediaRecorder = null;
            }
        }
    }

    private void fadeTo(View view, float targetAlpha, int duration) {
        if (view == null)
            return;

        float currentAlpha = view.getAlpha();
        if (Math.abs(currentAlpha - targetAlpha) < 0.01f)
            return;

        view.animate().alpha(targetAlpha).setDuration(duration).start();
    }

    @Override
    protected void handleOnDestroy() {
        stopBackgroundThread();
        getActivity().runOnUiThread(this::cleanupResources);
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to join background thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void rejectIfPossible(String errorMessage) {
        if (storedCall == null) {
            Log.w(TAG, "storedCall is null. Could not reject: " + errorMessage);
            return;
        }

        JSObject result = new JSObject();
        result.put("videoPath", "");
        result.put("error", errorMessage);
        result.put("frameRate", videoFrameRate);
        result.put("resolution", selectedSize != null ? selectedSize.getWidth() + "x" + selectedSize.getHeight() : "");
        result.put("duration", 0);
        result.put("sizeLimit", sizeLimit);
        result.put("fileSizeMB", 0);

        Log.d(TAG, "Result JSON: " + result.toString());

        storedCall.resolve(result);
        storedCall = null;
    }

    private String getCameraErrorMessage(int errorCode) {
        switch (errorCode) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "ERROR_CAMERA_IN_USE (1): Camera is already in use by another app.";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "ERROR_MAX_CAMERAS_IN_USE (2): Too many cameras are in use simultaneously.";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "ERROR_CAMERA_DISABLED (3): Camera is disabled due to device policy.";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "ERROR_CAMERA_DEVICE (4): Fatal error. Camera must be closed and reopened.";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "ERROR_CAMERA_SERVICE (5): Camera service has crashed. Restart required.";
            default:
                return "UNKNOWN CAMERA ERROR (" + errorCode + ")";
        }
    }

}