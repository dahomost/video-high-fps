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
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.graphics.Rect;

// ONNX
import android.graphics.Bitmap;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.PoseLandmark;
import android.speech.tts.TextToSpeech;
import com.google.mlkit.vision.pose.Pose;

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

    // ONNX
    private onnxPreChecking preCheck;
    private PoseDetector poseDetector;

    // Pose detection handler with 1-second interval
    private final Handler poseHandler = new Handler();
    private int poseDetectionInterval = 1000; // Start with 1 second

    private FeedbackHelper feedbackHelper;
    private final AtomicBoolean isTtsInitializing = new AtomicBoolean(false);
    private static final int TTS_INIT_DELAY_MS = 500;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Safely initializes the FeedbackHelper with TTS capabilities
     * Ensures only one initialization happens at a time
     */
    private void initializeFeedbackHelper() {
        // Already initialized or in progress
        if (feedbackHelper != null || isTtsInitializing.get()) {
            return;
        }

        // Set initialization flag
        isTtsInitializing.set(true);

        getActivity().runOnUiThread(() -> {
            try {
                Log.d(TAG, "Initializing FeedbackHelper...");
                initializeFeedbackHelper();

                // Set up delayed check for TTS readiness
                mainHandler.postDelayed(() -> {
                    isTtsInitializing.set(false);
                    if (feedbackHelper != null) {
                        Log.d(TAG, "FeedbackHelper initialization complete");
                    } else {
                        Log.e(TAG, "FeedbackHelper initialization failed");
                    }
                }, TTS_INIT_DELAY_MS);

            } catch (Exception e) {
                isTtsInitializing.set(false);
                Log.e(TAG, "Failed to initialize FeedbackHelper", e);
            }
        });
    }

    /**
     * Safe wrapper for speaking with beeps that handles null checks
     */
    private void safeSpeakWithBeeps(String message, int beeps, long delay, Runnable action) {
        if (feedbackHelper == null) {
            Log.w(TAG, "Cannot speak - FeedbackHelper not initialized");
            // Optionally queue the message or retry initialization
            return;
        }

        getActivity().runOnUiThread(() -> {
            try {
                feedbackHelper.speakWithBeeps(message, beeps, delay, action);
            } catch (Exception e) {
                Log.e(TAG, "Error in safeSpeakWithBeeps", e);
            }
        });
    }

    /**
     * Clean up FeedbackHelper resources
     */
    private void cleanupFeedbackHelper() {
        if (feedbackHelper != null) {
            try {
                feedbackHelper.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down FeedbackHelper", e);
            }
            feedbackHelper = null;
        }
        isTtsInitializing.set(false);
    }

    private final Runnable poseRunnable = new Runnable() {
        @Override
        public void run() {
            if (textureView != null && textureView.isAvailable()) {
                // Convert the current frame into a Bitmap
                Bitmap bitmap = textureView.getBitmap();

                if (bitmap != null) {
                    // Convert Bitmap to InputImage
                    InputImage image = InputImage.fromBitmap(bitmap, 0); // The second argument is the rotation (if any)

                    // Process the frame with pose detection
                    poseDetector.process(image)
                            .addOnSuccessListener(pose -> {
                                if (pose != null) {
                                    // Validate the pose
                                    validatePoseAndFeedback(pose, textureView.getWidth(), textureView.getHeight());
                                }
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Pose detection failed", e);
                                poseDetectionInterval = 2000; // Increase interval after failure
                                poseHandler.postDelayed(this, poseDetectionInterval); // Retry after a longer interval
                            });
                } else {
                    Log.w(TAG, "Failed to get bitmap from TextureView");
                }
            }

            // Re-run after dynamic interval (default 1 second or adjusted based on
            // conditions)
            poseHandler.postDelayed(this, poseDetectionInterval);
        }
    };

    // Start pose detection with dynamic interval
    public void startPoseDetection() {
        poseHandler.post(poseRunnable); // Ensure this is inside a method that is executed
    }

    // timerHandler
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

        // Check permission before proceeding
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            return;
        }

        Log.d(TAG, "startRecording -> Permission granted...");

        // ✅ ONNX: Only lighting check
        if (preCheck == null) {
            preCheck = new onnxPreChecking(getContext());
        }

        // ✅ Safe FeedbackHelper initialization (no crash on null)
        initializeFeedbackHelper();

        // ONNX: Initialize pose detector (only once)
        if (poseDetector == null) {
            poseDetector = PoseDetection.getClient(new PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build());
        }

        // Read parameters for video recording
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

        // Start lighting check
        Log.d(TAG, "✅ [ONNX] Preparing lighting monitor...");
        getActivity().runOnUiThread(() -> {
            if (textureView != null && textureView.isAvailable()) {
                preCheck.startReactiveLightingCheck(textureView);
                Log.d(TAG, "✅ [ONNX] Lighting check started");
            } else {
                Log.w(TAG, "⚠️ [ONNX] TextureView not available yet");
            }
        });

        // Camera initialization and preview
        try {
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

            // Check Lighting and Pose after some time (recheck loop)
            new Handler().postDelayed(() -> {
                // First, check lighting
                preCheck.startReactiveLightingCheck(textureView); // Fixed here

                if (isLightingGood()) {
                    // Then, check pose
                    Log.d(TAG, "start checking pose -------------------------------------------");
                    preCheck.detectPoseFromPreview(textureView);
                } else {
                    Log.w(TAG, "Lighting is not good, retrying...");
                    preCheck.startReactiveLightingCheck(textureView); // Retry Lighting Check
                    return;
                }

                // Get the latest pose after the lighting check
                Pose latestPose = preCheck.getLatestPose(); // Ensure you are getting the most recent pose from preCheck

                // Validate pose
                if (latestPose != null && isPoseValid(latestPose, textureView.getWidth(), textureView.getHeight())) {
                    askToStartRecording();
                } else {
                    Log.w(TAG, "Pose is invalid, retrying...");
                    preCheck.detectPoseFromPreview(textureView); // Keep checking pose
                }
            }, 5000); // Check again after 5 seconds

        } catch (Exception e) {
            Log.e(TAG, "Failed to startRecording()", e);
            cleanupResources();
            stopBackgroundThread();
            call.reject("Failed to start recording: " + e.getMessage());
        }
    }

    private boolean isLightingGood() {
        return true;
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
            // ONNX: SurfaceTexture available callback
            // ONNX: Start the pose detection loop
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (selectedSize != null) {
                    surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                    previewSurface = new Surface(surface);
                    configureTransform(width, height);
                    openCamera();

                    // Start ONNX lighting check AFTER texture is available
                    getActivity().runOnUiThread(() -> {
                        if (preCheck == null) {
                            preCheck = new onnxPreChecking(getContext());
                            Log.d(TAG, "✅ [ONNX] Instance created after preview");
                        }

                        if (textureView != null && textureView.isAvailable()) {
                            preCheck.startReactiveLightingCheck(textureView);
                            Log.d(TAG, "✅ [ONNX] Lighting check started after preview");
                        } else {
                            Log.w(TAG, "⚠️ [ONNX] TextureView still not available");
                        }

                        // Start processing the pose detection
                        // processPoseDetection();
                    });

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
            //
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                // ONNX: This is where you will process the frame for pose detection
                processPoseDetection();
            }
        });

        setupUI(); // Adds textureView and UI buttons
    }

    // ONXX: Process the current frame for pose detection
    private void processPoseDetection() {
        if (textureView != null && textureView.isAvailable()) {
            Bitmap bitmap = textureView.getBitmap();

            if (bitmap == null) {
                Log.w(TAG, "Failed to get bitmap from TextureView");
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);

            // ✅ Capture helper early to avoid null in async callback
            final FeedbackHelper helper = this.feedbackHelper;

            poseDetector.process(image)
                    .addOnSuccessListener(pose -> {
                        if (pose != null) {
                            if (helper == null) {
                                Log.w(TAG, "⚠️ Captured helper is null – skipping validation.");
                                return;
                            }
                            // ✅ Pass the helper to avoid using the field directly
                            // validateShouldersAlignment(pose, helper);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Pose detection failed", e);
                    });
        } else {
            Log.w(TAG, "TextureView not available for pose detection");
        }
    }

    private boolean isTTSInProgress = false; // Flag to track if TTS is in progress

    // ONNX: Validate shoulders alignment and provide feedback
    private void validateShouldersAlignment(Pose pose) {
        // Get all pose landmarks
        List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();
        PoseLandmark leftShoulder = null;
        PoseLandmark rightShoulder = null;

        for (PoseLandmark landmark : landmarks) {
            if (landmark.getLandmarkType() == PoseLandmark.LEFT_SHOULDER) {
                leftShoulder = landmark;
            } else if (landmark.getLandmarkType() == PoseLandmark.RIGHT_SHOULDER) {
                rightShoulder = landmark;
            }
        }

        if (leftShoulder != null && rightShoulder != null) {
            float leftShoulderX = leftShoulder.getPosition().x;
            float rightShoulderX = rightShoulder.getPosition().x;

            if (Math.abs(leftShoulderX - rightShoulderX) < 50) {
                if (!isTTSInProgress) {
                    isTTSInProgress = true;
                    helper.speakWithBeeps("Pose looks good, you're aligned!", 2, 1500,
                            () -> isTTSInProgress = false);
                }
            } else {
                if (!isTTSInProgress) {
                    isTTSInProgress = true;
                    helper.speakWithBeeps("Please align your shoulders!", 2, 1500,
                            () -> isTTSInProgress = false);
                }
            }
        } else {
            if (!isTTSInProgress) {
                isTTSInProgress = true;
                helper.speakWithBeeps("Failed to detect shoulders.", 2, 1500,
                        () -> isTTSInProgress = false);
            }
        }
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

        // Grid Overlay added just after textureView
        GridOverlay gridOverlay = new GridOverlay(activity);
        gridOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.addView(gridOverlay);

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

            surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
            previewSurface = new Surface(surfaceTexture);

            safeReleaseMediaRecorder();
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
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                new Range<>(videoFrameRate, videoFrameRate));

                        hsSession.setRepeatingBurst(
                                hsSession.createHighSpeedRequestList(builder.build()),
                                null,
                                backgroundHandler);

                        captureSession = session;

                        // ✅ Show toast with selected resolution + FPS
                        getActivity().runOnUiThread(() -> {
                            recordButton.setVisibility(View.VISIBLE);
                            Toast.makeText(getContext(),
                                    "Ready: " + videoFrameRate + "fps @ " +
                                            selectedSize.getWidth() + "x" + selectedSize.getHeight(),
                                    Toast.LENGTH_SHORT).show();
                        });

                        onPreviewSuccess(); // Handles fade-in + UI

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
            Log.e(TAG, "Exception during high-speed setup. Trying lower FPS fallback.", e);
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
        Log.d(TAG, " startStandardCaptureSession() called");

        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            Log.e(TAG, "Surface texture is null");
            throw new IllegalStateException("Surface texture not available");
        }

        surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
        previewSurface = new Surface(surfaceTexture);
        Log.d(TAG, " Preview surface set: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());

        safeReleaseMediaRecorder();

        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();
        if (recorderSurface == null) {
            Log.e(TAG, " Recorder surface is null after prepare()");
            throw new IllegalStateException("Recorder surface is null after prepare()");
        }

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(previewSurface);
        surfaces.add(recorderSurface);

        Log.d(TAG, "🎥 Creating standard camera session...");
        cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                Log.d(TAG, "✅ Standard session configured");
                captureSession = session;

                try {
                    CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(previewSurface);
                    builder.addTarget(recorderSurface);
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            new Range<>(videoFrameRate, videoFrameRate));

                    Log.d(TAG, "⚡ Repeating standard request: " + videoFrameRate + "fps");
                    session.setRepeatingRequest(builder.build(), null, backgroundHandler);

                    textureView.post(() -> {
                        configureTransform(textureView.getWidth(), textureView.getHeight());
                        fadeTo(textureView, 1f, 300);

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

                        if (blackOverlayView != null) {
                            ViewGroup root = (ViewGroup) getActivity().findViewById(android.R.id.content);
                            root.removeView(blackOverlayView);
                            blackOverlayView = null;
                        }
                    });

                    getActivity().runOnUiThread(() -> {
                        Log.d(TAG, "✅ UI ready — preview should be visible now");
                        recordButton.setVisibility(View.VISIBLE);
                        Toast.makeText(getContext(), "Ready: " + videoFrameRate + "fps " +
                                selectedSize.getWidth() + "x" + selectedSize.getHeight(), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to start preview: " + e.getMessage(), e);
                    rejectIfPossible("Failed to start preview: " + e.getMessage());
                    getActivity().runOnUiThread(() -> cleanupResources());
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Log.e(TAG, "❌ Standard configuration failed");
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

        // ONNX cleanup feedback helper
        cleanupFeedbackHelper();

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

            // clean preCheck
            if (preCheck != null) {
                preCheck.cleanup();
                preCheck = null;
                Log.d(TAG, "1 Pre-check clean up <<<<<<<<<<<<<<<");
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
            if (preCheck != null) {
                preCheck.stopReactiveLightingCheck();
                Log.d(TAG, "✅ Lighting check stopped in stopRecording()");
            }

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

    // ========================================

    // Validate the pose and give feedback
    private void validatePoseAndFeedback(Pose pose, int previewWidth, int previewHeight) {
        if (isPoseValid(pose, previewWidth, previewHeight)) {
            askToStartRecording();
        } else {
            Log.w(TAG, "Pose is invalid, retrying...");
            // Retry the pose detection after a short delay, if needed

            if (textureView == null || !textureView.isAvailable()) {
                Log.w(TAG, "❌ TextureView not available, skipping pose recheck.");
                return;
            }

            // Run pose detection on the UI thread after a delay to prevent tight loops
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    preCheck.detectPoseFromPreview(textureView);
                } catch (Exception e) {
                    Log.e(TAG, "⚠️ Failed to run pose detection: " + e.getMessage());
                }
            }, 1000); // Delay avoids recursion flood

        }
    }

    // Ask the user if they're ready to start recording
    private void askToStartRecording() {
        feedbackHelper.speakWithBeeps("You're good to go, can I start recording now?", 1, 3000, () -> {
            // Wait for response, if "Yes", start recording
            // If response is "No", ask again after 20 seconds
            // This requires integrating voice recognition to capture the answer (e.g.
            // SpeechRecognizer)
        });
    }

    // Updated isPoseValid to check alignment and distance
    private boolean isPoseValid(Pose pose, int previewWidth, int previewHeight) {
        if (pose == null || pose.getAllPoseLandmarks().isEmpty()) {
            Log.w(TAG, "Pose is invalid: no landmarks detected.");
            return false; // Pose is invalid if there are no landmarks
        }

        // Initialize bounding box coordinates for the detected pose
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (PoseLandmark landmark : pose.getAllPoseLandmarks()) {
            float x = landmark.getPosition().x;
            float y = landmark.getPosition().y;

            // Find bounding box coordinates (min/max)
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        // Calculate center and width of the bounding box
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float bboxWidth = maxX - minX;

        Log.d(TAG, "📐 Pose Center: x=" + centerX + ", y=" + centerY + ", bbox width=" + bboxWidth);

        // Check if the person is centered in the frame (assuming centered around 50% of
        // the preview width)
        boolean isCentered = centerX > previewWidth * 0.3 && centerX < previewWidth * 0.7;

        // Check if the person is too close (based on the width of the detected pose)
        boolean isTooClose = bboxWidth > previewWidth * 0.6;

        if (!isCentered) {
            Log.d(TAG, "Position is not centered.");
        }
        if (isTooClose) {
            Log.d(TAG, "Position is too close.");
        }

        // If the pose is not centered or the person is too close, it's an invalid pose
        if (!isCentered || isTooClose) {
            return false;
        }

        // If pose is valid
        return true;
    }

    // ONNX: Run lighting and pose checks in a safe loop
    private void runPoseValidationLoop() {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "❌ TextureView not available. Skipping ONNX loop.");
            return;
        }

        // ✅ Fix: Make sure preCheck is initialized
        if (preCheck == null) {
            preCheck = new onnxPreChecking(getContext());
            Log.d(TAG, "[ONNX] Initialized preCheck inside runPoseValidationLoop()");
        }

        Log.d(TAG, "🔁 Starting ONNX pose validation loop...");

        preCheck.sayLightIsGood(() -> {
            Log.d(TAG, "✅ Lighting feedback done, now running pose detection...");

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Log.d(TAG, "📸 Calling detectPoseFromPreview...");
                    preCheck.detectPoseFromPreview(textureView);
                } catch (Exception e) {
                    Log.e(TAG, "💥 Exception in detectPoseFromPreview: " + e.getMessage(), e);
                    return;
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "⏳ Attempting to get latest pose...");
                    Pose pose = null;
                    try {
                        pose = preCheck.getLatestPose();
                        Log.d(TAG, "📥 Pose retrieved: " + (pose != null ? "✅ Not null" : "❌ Null"));
                    } catch (Exception e) {
                        Log.e(TAG, "💥 Exception in getLatestPose(): " + e.getMessage(), e);
                    }

                    if (pose != null && isPoseValid(pose, textureView.getWidth(), textureView.getHeight())) {
                        Log.d(TAG, "✅ Pose is valid, asking to start recording...");
                        askToStartRecording();
                    } else {
                        Log.w(TAG, "❌ Pose invalid or null, retrying...");
                        preCheck.speakPoseNotValid(() -> {
                            Log.d(TAG, "🔁 Retrying pose validation...");
                            runPoseValidationLoop();
                        });
                    }

                }, 1200);
            });
        });
    }

}