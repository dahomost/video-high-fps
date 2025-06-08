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
import android.graphics.RectF;
import android.annotation.SuppressLint;

@CapacitorPlugin(name = "VideoHighFps", permissions = {
        @Permission(strings = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, alias = "camera")
})
public class VideoHighFpsPlugin extends Plugin {
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String videoPath;
    private CameraManager cameraManager;
    private Handler backgroundHandler;

    private Size selectedSize = new Size(1280, 720);
    private String selectedCameraId = null;
    private int videoFrameRate = 120;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private PluginCall storedCall;
    private TextureView textureView;
    private Surface previewSurface;
    private FrameLayout overlay;

    private Button recordButton, pauseButton, stopButton, backButton;
    private TextView timerView;
    private final Handler timerHandler = new Handler();
    private long startTime;

    @PluginMethod
    public void openCamera(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            Log.d("VideoHighFps", "--> open camera, permission denied");
            return;
        }

        Log.d("VideoHighFps", "--> open camera, permission granted");

        storedCall = call;
        Integer frameRateValue = call.getInt("frameRate", 120);
        videoFrameRate = (frameRateValue != null) ? frameRateValue : 120;
        // videoFrameRate = call.getInt("frameRate", 120);

        Context context = getContext();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        HandlerThread handlerThread = new HandlerThread("CameraBackground");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());

        try {
            selectedCameraId = getBackCameraId();
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
            Log.d("VideoHighFps", "--> permission granted");
            openCamera(call);
        } else {
            Log.d("VideoHighFps", "--> permission not granted");
            call.reject("Permission denied");
        }
    }

    private void openNativeCamera() throws CameraAccessException {
        Activity activity = getActivity();

        if (ActivityCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            storedCall.reject("Camera permission not granted");
            return;
        }

        showControlButton();

        cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
                try {
                    startCaptureSession();
                } catch (Exception e) {
                    storedCall.reject("Camera session error: " + e.getMessage());
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
            }

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
                Log.e("VideoHighFps", "Back button error: " + e.getMessage());
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
        mediaRecorder.setVideoEncodingBitRate(15_000_000);
        mediaRecorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());

        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 90;
        switch (rotation) {
            case Surface.ROTATION_90:
                degrees = 0;
                break;
            case Surface.ROTATION_180:
                degrees = 270;
                break;
            case Surface.ROTATION_270:
                degrees = 180;
                break;
            case Surface.ROTATION_0:
                break;
        }
        mediaRecorder.setOrientationHint(degrees);
        mediaRecorder.prepare();
        Thread.sleep(500);
    }

    private void startCaptureSession() throws Exception {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert configMap != null;
        Size[] highSpeedSizes = configMap.getHighSpeedVideoSizes();
        Size bestSize = null;

        for (Size size : highSpeedSizes) {
            Range<Integer>[] fpsRanges = configMap.getHighSpeedVideoFpsRangesFor(size);
            for (Range<Integer> range : fpsRanges) {
                Log.d("VideoHighFps", "🔍 Size " + size + " supports FPS range: " + range);
                if (range.contains(120)) {
                    if (bestSize == null
                            || (size.getWidth() >= bestSize.getWidth() && size.getHeight() >= bestSize.getHeight())) {
                        bestSize = size;
                    }
                }
            }
        }

        if (bestSize == null)
            throw new Exception("❌ No resolution supports 120fps on this device");

        selectedSize = bestSize;
        Log.d("VideoHighFps", "Using size for 120fps: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());

        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();
        java.util.List<Surface> surfaces = Arrays.asList(previewSurface, recorderSurface);

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
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(120, 120));
                            hsSession.setRepeatingBurst(hsSession.createHighSpeedRequestList(builder.build()), null,
                                    backgroundHandler);
                        } catch (Exception e) {
                            storedCall.reject("Capture failed: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        storedCall.reject("High-speed configuration failed");
                    }
                }, backgroundHandler);
    }

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

            JSObject ret = new JSObject();
            ret.put("videoPath", videoPath);
            storedCall.resolve(ret);
        } catch (Exception e) {
            storedCall.reject("Stop failed: " + e.getMessage());
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (textureView == null || selectedSize == null)
            return;

        Matrix matrix = new Matrix();
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, selectedSize.getHeight(), selectedSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / selectedSize.getHeight(),
                    (float) viewWidth / selectedSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }

        textureView.setTransform(matrix);
    }
}
