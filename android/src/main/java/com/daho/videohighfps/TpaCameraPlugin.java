package com.daho.videohighfps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.graphics.Rect;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.ParcelFileDescriptor;
import android.media.MediaScannerConnection;

@CapacitorPlugin(name = "TpaCamera", permissions = {
        @Permission(strings = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
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
    private Button recordButton, pauseButton, stopButton, backButton;
    private TextView timerView;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private long startTime;
    private String videoPath;
    private PluginCall storedCall;
    private Size selectedSize;
    private String selectedCameraId;
    private int videoFrameRate;
    private int sizeLimit;

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
        try {
            if (getPermissionState("camera") != PermissionState.GRANTED) {
                requestPermissionForAlias("camera", call, "onCameraPermissionResult");
                return;
            }

            storedCall = call;

            videoFrameRate = call.getInt("fps", 240); // Default to 240 fps
            sizeLimit = call.getInt("sizeLimit", 0);
            String quality = call.getString("resolution", "1080p"); // Default to 1080p

            // LOG INCOMING PARAMETERS
            Log.d(TAG, "[startRecording] Params:");
            Log.d(TAG, " - fps: " + videoFrameRate);
            Log.d(TAG, " - sizeLimit: " + sizeLimit);
            Log.d(TAG, " - resolution: " + quality);

            Context context = getContext();
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            selectedCameraId = getPreferredCameraId();
            selectOptimalConfiguration(quality);
            startBackgroundThread();

            // -> Hide WebView before showing camera
            getActivity().runOnUiThread(() -> {
                try {
                    View webView = getBridge().getWebView();
                    if (webView != null) {
                        webView.setVisibility(View.GONE);
                        Log.d(TAG, "WebView hidden for full-screen native recording");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to hide WebView", e);
                }
            });

            showCameraPreview();

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            call.reject("Failed to start recording: " + e.getMessage());
            cleanupResources();
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

    private void selectOptimalConfiguration(String quality) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (configMap == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Camera configuration not available");
        }

        // Define target resolutions based on quality
        Size[] targetSizes;
        switch (quality.toLowerCase()) {
            case "4k":
                targetSizes = new Size[] { new Size(3840, 2160), new Size(1920, 1080), new Size(1280, 720) };
                break;
            case "1080p":
                targetSizes = new Size[] { new Size(1920, 1080), new Size(1280, 720) };
                break;
            default:
                targetSizes = new Size[] { new Size(1280, 720) };
                break;
        }

        // Get supported high-speed sizes if requested FPS is high
        Size[] highSpeedSizes = (videoFrameRate >= 120) ? configMap.getHighSpeedVideoSizes() : null;
        List<Size> supportedSizes = new ArrayList<>();
        if (highSpeedSizes != null && highSpeedSizes.length > 0) {
            supportedSizes.addAll(Arrays.asList(highSpeedSizes));
        } else {
            supportedSizes.addAll(Arrays.asList(configMap.getOutputSizes(MediaRecorder.class)));
        }

        // Filter sizes that match target resolutions
        Size bestSize = null;
        int bestFps = 0;
        int[] targetFpsOptions = { videoFrameRate, 120, 60, 30 }; // Try requested FPS, then fall back

        for (Size size : supportedSizes) {
            for (Size targetSize : targetSizes) {
                if (size.getWidth() == targetSize.getWidth() && size.getHeight() == targetSize.getHeight()) {
                    Range<Integer>[] fpsRanges = configMap.getHighSpeedVideoFpsRangesFor(size);
                    if (fpsRanges == null || fpsRanges.length == 0) {
                        fpsRanges = new Range[] { new Range<>(30, 30) }; // Fallback for standard mode
                    }
                    for (Range<Integer> fpsRange : fpsRanges) {
                        for (int targetFps : targetFpsOptions) {
                            if (fpsRange.contains(targetFps)) {
                                if (bestSize == null || targetFps > bestFps ||
                                        (targetFps == bestFps && size.getWidth()
                                                * size.getHeight() > bestSize.getWidth() * bestSize.getHeight())) {
                                    bestSize = size;
                                    bestFps = targetFps;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (bestSize == null) {
            bestSize = new Size(1280, 720);
            bestFps = 30;
            Log.w(TAG, "No matching configuration found, using fallback: 1280x720 @ 30fps");
        }

        selectedSize = bestSize;
        videoFrameRate = bestFps;
        Log.d(TAG, "Selected configuration: " + selectedSize.getWidth() + "x" + selectedSize.getHeight() + " @ "
                + videoFrameRate + "fps");

        // Ensure portrait orientation only if not high-speed (high-speed prefers native
        // orientation)
        if (videoFrameRate < 120 && selectedSize.getWidth() > selectedSize.getHeight()) {
            selectedSize = new Size(selectedSize.getHeight(), selectedSize.getWidth());
        }
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
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            startRecording(call);
        } else {
            call.reject("Camera permission denied");
        }
    }

    private void showCameraPreview() {
        Activity activity = getActivity();
        if (activity == null) {
            storedCall.reject("Activity not available");
            return;
        }

        textureView = new TextureView(activity);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                try {
                    surface.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
                    previewSurface = new Surface(surface);
                    configureTransform(width, height);
                    openCamera();
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

        setupUI();
    }

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

        recordButton = createStyledButton("🔴");
        pauseButton = createStyledButton("❘ ❘");
        stopButton = createStyledButton("■");
        backButton = createStyledButton("");

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

        overlay.addView(textureView);
        overlay.addView(timerView);
        overlay.addView(buttonsLayout);

        activity.runOnUiThread(() -> {
            FrameLayout root = activity.findViewById(android.R.id.content);
            root.addView(overlay);
        });

        setupButtonListeners();
    }

    private Button createStyledButton(String text) {
        Activity activity = getActivity();
        if (activity == null)
            return null;
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(24);
        button.setTextColor(0xFFFFFFFF);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xAA333333);
        button.setBackground(shape);
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
                if (isRecording) {
                    if (isPaused)
                        resumeRecording();
                    else
                        pauseRecording();
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

                    // -> Fix orientation after preview is fully active
                    textureView.post(() -> {
                        configureTransform(textureView.getWidth(), textureView.getHeight());
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
                    getActivity().runOnUiThread(() -> recordButton.setVisibility(View.VISIBLE));
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

        // 🔍 Scale only to match the width, keep full aspect ratio
        float scaleX = (float) viewWidth / selectedSize.getHeight(); // Use rotated width
        matrix.postScale(scaleX, scaleX, centerX, centerY); // Same scale for X and Y

        // 🔄 Rotate and fix upside-down
        matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        matrix.postScale(1, -1, centerX, centerY); // Fix vertical flip

        textureView.setTransform(matrix);
    }

    private int getRotationDegrees(int sensorOrientation, int displayRotation) {
        int rotation = 0;
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
        }

        int result = (sensorOrientation - rotation + 360) % 360;
        return result;
    }

    private void setupMediaRecorder() throws IOException {
        Log.d(TAG, "Initializing MediaRecorder using MediaStore in Movies/recordings...");

        // Define file metadata
        String fileName = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/recordings");

        Uri uri = getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("❌ Failed to create MediaStore entry");
        }

        videoPath = uri.toString(); // Save URI for frontend
        Log.d(TAG, "Saving to: " + videoPath);

        // Set up MediaRecorder
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        int bitrate = calculateBitrate(selectedSize, videoFrameRate);
        mediaRecorder.setVideoEncodingBitRate(bitrate);
        mediaRecorder.setVideoFrameRate(videoFrameRate);
        mediaRecorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());

        // Use the file descriptor from MediaStore URI
        mediaRecorder.setOutputFile(getContext().getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor());

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
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    Log.w(TAG, "MediaRecorder info: MAX FILE SIZE REACHED");
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                    Log.w(TAG, "MediaRecorder info: MAX DURATION REACHED");
                    break;
                default:
                    Log.w(TAG, "MediaRecorder info: what=" + what + ", extra=" + extra);
                    break;
            }
        });

        mediaRecorder.prepare();
        Log.d(TAG, " MediaRecorder prepared with MediaStore output.");
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
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }

            long durationMillis = SystemClock.elapsedRealtime() - startTime;
            float durationSec = durationMillis / 1000f;

            // Reject if duration too short
            if (durationSec < 0.5f) {
                Log.w(TAG, "❌ Recording too short or failed, deleting: " + videoPath);
                deleteVideoFromMediaStore(videoPath);
                storedCall.reject("Recording too short or failed");
                return;
            }

            JSObject result = new JSObject();
            result.put("videoPath", videoPath);
            result.put("frameRate", videoFrameRate);
            result.put("resolution", selectedSize.getWidth() + "x" + selectedSize.getHeight());
            result.put("duration", durationSec);
            result.put("sizeLimit", sizeLimit);

            Log.d(TAG, "[stopRecording] Result JSON:\n" + result.toString(2));
            storedCall.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
            storedCall.reject("Failed to stop recording: " + e.getMessage());
        } finally {
            cleanupResources();
        }
    }

    private void deleteVideoFromMediaStore(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            int deleted = getContext().getContentResolver().delete(uri, null, null);
            Log.w(TAG, "Deleted invalid video, count=" + deleted);
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete invalid video", e);
        }
    }

    private void pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder.pause();
                isPaused = true;
                timerHandler.removeCallbacks(timerRunnable);
                getActivity().runOnUiThread(() -> pauseButton.setText("▶"));
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
                getActivity().runOnUiThread(() -> pauseButton.setText("❘ ❘"));
            } catch (Exception e) {
                Log.e(TAG, "Failed to resume recording", e);
            }
        } else {
            startRecordingInternal();
        }
    }

    private void cancelRecording() {
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