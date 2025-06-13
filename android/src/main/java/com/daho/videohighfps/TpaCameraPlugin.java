package com.daho.videohighfps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import android.graphics.Matrix;

import android.annotation.SuppressLint;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

// Registers the plugin and declares needed Android permissions (like CAMERA, AUDIO) so they can be requested at runtime.
@CapacitorPlugin(name = "TpaCamera", permissions = {
        @Permission(strings = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, alias = "camera")
})

public class TpaCameraPlugin extends Plugin {

    // Active camera (opened via Camera2 API)
    private CameraDevice cameraDevice;

    // Manages capture requests for preview/recording
    private CameraCaptureSession captureSession;

    // Records video to file
    private MediaRecorder mediaRecorder;

    // Path where recorded video is saved
    private String videoPath;

    // Accesses available camera hardware
    private CameraManager cameraManager;

    // Runs camera tasks on a background thread
    private Handler backgroundHandler;

    // Desired video resolution (e.g. 1280×720)
    private Size selectedSize;

    // ID of the camera being used (e.g. rear camera)
    private String selectedCameraId = null;

    // Target recording FPS (e.g. 120)
    private int videoFrameRate;

    private boolean isRecording = false;
    private boolean isPaused = false;

    // Reference to JS call (used to resolve/reject later)
    private PluginCall storedCall;

    // Displays live camera preview
    private TextureView textureView;

    // Surface for camera preview (from textureView)
    private Surface previewSurface;

    // UI container for buttons/timer overlay
    private FrameLayout overlay;

    private Button recordButton, pauseButton, stopButton, backButton;

    // UI to show recording time
    private TextView timerView;

    // Runs timer updates
    private final Handler timerHandler = new Handler();

    // Time when recording started
    private long startTime;

    private int sizeLimit;

    @PluginMethod
    public void startRecording(PluginCall call) {

        // check permission

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            Log.d("TpaCamera", "--> open camera, permission denied");
            return;
        }

        Log.d("TpaCamera", "--> open camera, permission granted");

        // get params (frameRate, quality, )
        storedCall = call;

        this.videoFrameRate = call.getInt("fps", 240); // default 240
        this.sizeLimit = call.getInt("sizeLimit", 0); // 0 = unlimited (default)
        String quality = call.getString("resolution", "4k"); // default = 4k

        if ("4k".equalsIgnoreCase(quality)) {
            selectedSize = new Size(3840, 2160);
        } else if ("1080p".equalsIgnoreCase(quality)) {
            selectedSize = new Size(1920, 1080);
        } else {
            selectedSize = new Size(1280, 720); // default = 720p
        }

        Log.d("TpaCamera", "--> videoFrameRate = " + videoFrameRate);
        Log.d("TpaCamera", "--> quality = " + quality);
        Log.d("TpaCamera", "--> sizeLimit = " + sizeLimit);

        // Init CameraManager - Android Camera system
        // Gets the current Context (usually from an Activity, Fragment, or View).
        // Context is needed to access Android system services.
        // Retrieves the CameraManager system service, which lets you interact with the
        // Camera2 API (like opening cameras, querying available camera devices, etc.).

        Context context = getContext();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        // Creates a new background thread named "CameraBackground" that will be used to
        // offload heavy camera tasks from the main UI thread.
        HandlerThread handlerThread = new HandlerThread("CameraBackground");

        // Starts the background thread. It’s now alive and can handle queued
        // messages/tasks.
        handlerThread.start();

        // Creates a Handler associated with the background thread’s Looper.
        // This backgroundHandler can now be passed to Camera2 API methods that require
        // a non-UI thread for callbacks, image capture, or processing.
        backgroundHandler = new Handler(handlerThread.getLooper());

        try {
            // Calls a method getBackCameraId() which likely scans all available cameras and
            // returns the ID of the back-facing camera.
            selectedCameraId = getBackCameraId();

            // open the camera using the selected back camera ID and starts the preview
            // stream.
            openNativeCamera();
        } catch (Exception e) {
            call.reject("Camera error: " + e.getMessage());
        }
    }

    private String getBackCameraId() throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No back camera found");
    }

    @PermissionCallback
    private void onCameraPermissionResult(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            Log.d("TpaCamera", "--> permission granted");
            startRecording(call);
        } else {
            Log.d("TpaCamera", "--> permission not granted");
            call.reject("Permission denied");
        }
    }

    // opens the camera and starts the session.
    // throw a CameraAccessException if accessing the camera fails.
    private void openNativeCamera() throws CameraAccessException {

        // Gets the current Android Activity context, required for permission checks and
        // system-level calls.
        Activity activity = getActivity();

        // Checks whether the app has permission to use the camera.
        // If not granted, it rejects the storedCall (Capacitor plugin
        // PluginCall) with an error message and exits the method early.
        if (ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            storedCall.reject("Camera permission not granted");
            return;
        }

        // displays a UI overlay buttons (start/stop recording)
        showControlButton();

        // Attempts to open the camera with the ID stored
        // Provides a StateCallback to handle camera events: onOpened, onDisconnected,
        // and onError.
        cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {

            // Called when the camera has successfully opened.
            // Saves the opened camera in cameraDevice.
            // Starts a camera capture session (for preview or recording).
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
                try {
                    startCaptureSession();
                } catch (Exception e) {
                    storedCall.reject("Camera session error: " + e.getMessage());
                }
            }

            // Called when the camera disconnects (e.g., USB unplugged, app goes to
            // background). Closes the camera to release resources.
            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
            }

            // Called when a camera error occurs (e.g., device failure).
            // Closes the camera to clean up.
            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                camera.close();
            }
        }, backgroundHandler);
    }

    @SuppressLint("SetTextI18n")
    private void showControlButton() {
        Activity activity = getActivity();

        textureView = new TextureView(activity);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                previewSurface = new Surface(surface);
                configureTransform(width, height);
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

        recordButton = createStyledButton("🔴");
        pauseButton = createStyledButton("❘ ❘");
        stopButton = createStyledButton("■");
        backButton = createStyledButton("⨉");

        pauseButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);

        recordButton.setOnClickListener(v -> {
            try {
                mediaRecorder.start();
                isRecording = true;
                isPaused = false;
                startTime = SystemClock.elapsedRealtime();
                timerHandler.post(timerRunnable);
                recordButton.setVisibility(View.GONE);
                backButton.setVisibility(View.GONE);
                pauseButton.setVisibility(View.VISIBLE);
                stopButton.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                storedCall.reject("Button error: " + e.getMessage());
            }
        });

        backButton.setOnClickListener(v -> {
            try {
                if (captureSession != null)
                    captureSession.close();
                if (cameraDevice != null)
                    cameraDevice.close();
                if (mediaRecorder != null) {
                    mediaRecorder.reset(); // safe even if not started
                    mediaRecorder.release();
                    mediaRecorder = null;
                }
                getActivity().runOnUiThread(() -> {
                    FrameLayout root = getActivity().findViewById(android.R.id.content);
                    if (overlay != null)
                        root.removeView(overlay);
                });
            } catch (Exception e) {
                Log.e("TpaCamera", "Back button error: " + e.getMessage());
            }
        });

        pauseButton.setOnClickListener(v -> {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    if (!isPaused) {
                        mediaRecorder.pause();
                        isPaused = true;
                        pauseButton.setText("▶");
                        timerHandler.removeCallbacks(timerRunnable);
                    } else {
                        mediaRecorder.resume();
                        isPaused = false;
                        pauseButton.setText("❘ ❘");
                        timerHandler.post(timerRunnable);
                    }
                }
            } catch (Exception e) {
                storedCall.reject("Pause error: " + e.getMessage());
            }
        });

        stopButton.setOnClickListener(v -> {
            timerHandler.removeCallbacks(timerRunnable);
            stopNativeRecording();
        });

        timerView = new TextView(activity);
        timerView.setText("00:00");
        timerView.setTextSize(20);
        timerView.setTextColor(0xFFFFFFFF);
        timerView.setBackgroundColor(0xAA000000);
        timerView.setPadding(20, 10, 20, 10);
        FrameLayout.LayoutParams timerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        timerLp.setMargins(0, 60, 0, 0);
        timerView.setLayoutParams(timerLp);

        LinearLayout horizontalLayout = new LinearLayout(activity);
        horizontalLayout.setOrientation(LinearLayout.HORIZONTAL);
        horizontalLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams buttonRowParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonRowParams.setMargins(0, 0, 0, 80);
        horizontalLayout.setLayoutParams(buttonRowParams);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(200, 200);
        btnParams.setMargins(20, 0, 20, 0);
        recordButton.setLayoutParams(btnParams);
        backButton.setLayoutParams(btnParams);
        horizontalLayout.addView(backButton);
        horizontalLayout.addView(recordButton);

        FrameLayout.LayoutParams pauseLp = new FrameLayout.LayoutParams(160, 160, Gravity.BOTTOM | Gravity.START);
        pauseLp.setMargins(80, 0, 0, 100);
        pauseButton.setLayoutParams(pauseLp);

        FrameLayout.LayoutParams stopLp = new FrameLayout.LayoutParams(160, 160, Gravity.BOTTOM | Gravity.END);
        stopLp.setMargins(0, 0, 80, 100);
        stopButton.setLayoutParams(stopLp);

        overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.addView(textureView);
        overlay.addView(timerView);
        overlay.addView(horizontalLayout);
        overlay.addView(pauseButton);
        overlay.addView(stopButton);

        activity.runOnUiThread(() -> {
            FrameLayout root = activity.findViewById(android.R.id.content);
            root.addView(overlay);
        });
    }

    private Button createStyledButton(String icon) {
        Button btn = new Button(getActivity());
        btn.setText(icon);
        btn.setTextSize(28);
        btn.setTextColor(0xFFFFFFFF);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(1152035498);
        shape.setSize(180, 180);
        btn.setBackground(shape);
        return btn;
    }

    private final Runnable timerRunnable = new Runnable() {
        @SuppressLint("DefaultLocale")
        public void run() {
            long elapsed = SystemClock.elapsedRealtime() - startTime;
            int seconds = (int) (elapsed / 1000);
            int minutes = seconds / 60;
            seconds = seconds % 60;
            timerView.setText(String.format("%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 1000);
        }
    };

    // Verify setupMediaRecorder uses videoFrameRate (no change needed if already
    // correct)
    // Automatically adjust video bitrate based on resolution and fps
    private void setupMediaRecorder() throws Exception {
        File outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        String fileName = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        videoPath = new File(outputDir, fileName).getAbsolutePath();

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(videoPath);
        mediaRecorder.setVideoFrameRate(videoFrameRate);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        int pixels = selectedSize.getWidth() * selectedSize.getHeight();
        int bitrate;

        // Heuristic bitrate calculation based on resolution and fps
        if (pixels >= 3840 * 2160) { // 4K
            bitrate = videoFrameRate >= 240 ? 40_000_000 : 30_000_000;
        } else if (pixels >= 1920 * 1080) { // Full HD
            bitrate = videoFrameRate >= 240 ? 30_000_000 : 20_000_000;
        } else if (pixels >= 1280 * 720) { // HD
            bitrate = videoFrameRate >= 240 ? 20_000_000 : 10_000_000;
        } else {
            bitrate = 8_000_000;
        }

        mediaRecorder.setVideoEncodingBitRate(bitrate);
        Log.d("TpaCamera", "--> Bitrate selected: " + bitrate);

        mediaRecorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());

        if (sizeLimit > 0) {
            mediaRecorder.setMaxFileSize(sizeLimit);
        }

        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 90;
        // switch (rotation) {
        // case Surface.ROTATION_90:
        // degrees = 0;
        // break;
        // case Surface.ROTATION_180:
        // degrees = 270;
        // break;
        // case Surface.ROTATION_270:
        // degrees = 180;
        // break;
        // case Surface.ROTATION_0:
        // break;
        // }
        mediaRecorder.setOrientationHint(degrees);
        mediaRecorder.prepare();
        Thread.sleep(500);
    }

    // opens the camera and starts the session.
    // throw a CameraAccessException if accessing the camera fails.
    private void startCaptureSession() throws Exception {
        // Log the original input params for debugging
        Log.d("TpaCamera", "-- Input Params --");
        Log.d("TpaCamera", "Requested Frame Rate: " + videoFrameRate);
        Log.d("TpaCamera", "Preferred Resolution: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
        Log.d("TpaCamera", "Max File Size Limit: " + sizeLimit);

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert configMap != null;

        Size[] highSpeedSizes = configMap.getHighSpeedVideoSizes();
        Size[] previewSizes = configMap.getOutputSizes(SurfaceTexture.class);
        List<Size> previewList = Arrays.asList(previewSizes);

        Log.d("TpaCamera", "-- Device High Speed Capabilities --");
        for (Size size : highSpeedSizes) {
            Range<Integer>[] fpsRanges = configMap.getHighSpeedVideoFpsRangesFor(size);
            for (Range<Integer> range : fpsRanges) {
                Log.d("TpaCamera",
                        "Supports size: " + size.getWidth() + "x" + size.getHeight() + " with FPS range: " + range);
            }
        }

        Size bestSize = null;
        int bestFps = 0;

        Set<Integer> uniqueFps = new LinkedHashSet<>(Arrays.asList(videoFrameRate, 240, 120, 60, 30));

        int[] targetFpsOptions = new int[uniqueFps.size()];
        int index = 0;
        for (Integer val : uniqueFps) {
            targetFpsOptions[index++] = val;
        }

        for (int targetFps : targetFpsOptions) {
            for (Size size : highSpeedSizes) {
                if (!previewList.contains(size))
                    continue;
                Range<Integer>[] fpsRanges = configMap.getHighSpeedVideoFpsRangesFor(size);
                for (Range<Integer> range : fpsRanges) {
                    if (range.contains(targetFps)) {
                        if (bestSize == null || isHigherFpsOrResolution(targetFps, size, bestFps, bestSize)) {
                            bestSize = size;
                            bestFps = targetFps;
                            Log.d("TpaCamera", "--> Matched FPS: " + targetFps + " @ Resolution: " + size.getWidth()
                                    + "x" + size.getHeight());
                        }
                    }
                }
            }
        }

        boolean useHighSpeedSession = false;

        if (bestSize != null) {
            selectedSize = bestSize;
            videoFrameRate = bestFps;
            useHighSpeedSession = videoFrameRate >= 120;
            Log.d("TpaCamera", "--> FINAL SELECTED (Recording): " + videoFrameRate + "fps @ " + selectedSize.getWidth()
                    + "x" + selectedSize.getHeight());
        } else {
            videoFrameRate = 30;
            Size[] fallbackSizes = configMap.getOutputSizes(MediaRecorder.class);
            selectedSize = fallbackSizes.length > 0 ? fallbackSizes[0] : new Size(1280, 720);
            Log.d("TpaCamera", "--> FALLBACK: 30fps @ " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
        }

        if (textureView != null && textureView.isAvailable()) {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                configureTransformCrop(textureView.getWidth(), textureView.getHeight());
                Log.d("TpaCamera", "--> PREVIEW SETUP: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
            }
        }

        Integer lightLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null
                ? characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getLower()
                : null;
        if (lightLevel != null && lightLevel < 100) {
            Log.w("TpaCamera",
                    "⚠️ Low light environment detected – consider improving lighting for better 240fps results.");
        }

        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();
        List<Surface> surfaces = Arrays.asList(previewSurface, recorderSurface);

        if (useHighSpeedSession) {
            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CameraConstrainedHighSpeedCaptureSession hsSession = (CameraConstrainedHighSpeedCaptureSession) session;
                                CaptureRequest.Builder builder = cameraDevice
                                        .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(previewSurface);
                                builder.addTarget(recorderSurface);
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        new Range<>(videoFrameRate, videoFrameRate));

                                Log.d("TpaCamera", "--> PREVIEW STARTED @ " + selectedSize.getWidth() + "x"
                                        + selectedSize.getHeight() + " @ " + videoFrameRate + "fps");

                                hsSession.setRepeatingBurst(
                                        hsSession.createHighSpeedRequestList(builder.build()), null, backgroundHandler);
                            } catch (Exception e) {
                                storedCall.reject("Capture failed: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            storedCall.reject("High-speed configuration failed");
                        }
                    },
                    backgroundHandler);
        } else {
            cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder = cameraDevice
                                        .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(previewSurface);
                                builder.addTarget(recorderSurface);
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        new Range<>(videoFrameRate, videoFrameRate));

                                Log.d("TpaCamera", "--> PREVIEW STARTED @ " + selectedSize.getWidth() + "x"
                                        + selectedSize.getHeight() + " @ " + videoFrameRate + "fps");

                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (Exception e) {
                                storedCall.reject("Capture failed: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            storedCall.reject("Standard configuration failed");
                        }
                    },
                    backgroundHandler);
        }
    }

    // Prefer higher fps first, then resolution if fps are equal
    private boolean isHigherFpsOrResolution(int newFps, Size newSize, int currentFps, Size currentSize) {
        if (newFps > currentFps) {
            return true;
        } else if (newFps == currentFps) {
            int newPixels = newSize.getWidth() * newSize.getHeight();
            int currentPixels = currentSize.getWidth() * currentSize.getHeight();
            return newPixels > currentPixels;
        }
        return false;
    }

    // Update stopNativeRecording to include frame rate, resolution, and duration in
    // response
    private void stopNativeRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }

            if (captureSession != null)
                captureSession.close();
            if (cameraDevice != null)
                cameraDevice.close();

            getActivity().runOnUiThread(() -> {
                FrameLayout root = getActivity().findViewById(android.R.id.content);
                if (overlay != null)
                    root.removeView(overlay);
            });

            long durationMillis = SystemClock.elapsedRealtime() - startTime;
            float durationSeconds = durationMillis / 1000f;

            JSObject ret = new JSObject();
            ret.put("videoPath", videoPath);
            ret.put("frameRate", videoFrameRate);
            ret.put("resolution", selectedSize.getWidth() + "x" + selectedSize.getHeight());
            ret.put("duration", durationSeconds);
            ret.put("sizeLimit", sizeLimit);

            Log.d("TpaCamera", "--> return : " + ret.toString(2));

            storedCall.resolve(ret);
        } catch (Exception e) {
            storedCall.reject("Stop failed: " + e.getMessage());
        }
    }

    // Fix distorted preview scaling to preserve aspect ratio using fit-center
    // transform
    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || selectedSize == null)
            return;

        float previewWidth = selectedSize.getWidth();
        float previewHeight = selectedSize.getHeight();
        float viewRatio = (float) viewWidth / viewHeight;
        float previewRatio = previewWidth / previewHeight;

        float scale;
        float dx = 0f, dy = 0f;

        if (previewRatio > viewRatio) {
            scale = (float) viewWidth / previewWidth;
            dy = (viewHeight - previewHeight * scale) / 2f;
        } else {
            scale = (float) viewHeight / previewHeight;
            dx = (viewWidth - previewWidth * scale) / 2f;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);

        textureView.setTransform(matrix);
        Log.d("TpaCamera", "Fixed preview transform (fit center) scale=" + scale + ", dx=" + dx + ", dy=" + dy);
    }

    // Helper method to compare resolution quality against preferred resolution
    // Returns true if the candidate resolution is better (closer to preferred or
    // larger)
    private boolean isBetterMatch(Size candidate, Size currentBest, Size preferred) {
        if (currentBest == null)
            return true;

        int candidatePixels = candidate.getWidth() * candidate.getHeight();
        int currentPixels = currentBest.getWidth() * currentBest.getHeight();
        int preferredPixels = preferred.getWidth() * preferred.getHeight();

        // Prefer closer to preferred size (e.g. 4K), not smaller
        int candidateDiff = Math.abs(candidatePixels - preferredPixels);
        int currentDiff = Math.abs(currentPixels - preferredPixels);

        return candidatePixels >= preferredPixels && candidateDiff < currentDiff;
    }

    // Apply center-crop scaling to ensure full screen without stretching.
    private void configureTransformCrop(int viewWidth, int viewHeight) {
        if (textureView == null || selectedSize == null)
            return;

        float previewWidth = selectedSize.getWidth();
        float previewHeight = selectedSize.getHeight();
        float viewRatio = (float) viewWidth / viewHeight;
        float previewRatio = previewWidth / previewHeight;

        float scaleX, scaleY;
        if (previewRatio > viewRatio) {
            scaleX = previewRatio / viewRatio;
            scaleY = 1f;
        } else {
            scaleY = viewRatio / previewRatio;
            scaleX = 1f;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(matrix);
        Log.d("TpaCamera", "Preview transform (center-crop): scaleX=" + scaleX + ", scaleY=" + scaleY);
    }

}
