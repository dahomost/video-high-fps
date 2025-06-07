package com.daho.videohighfps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

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

    private int videoDuration = 0;
    private int videoFrameRate = 120;
    private int videoSizeLimit = 0;
    private boolean isRecording = false;
    private PluginCall storedCall;

    @PluginMethod
    public void openCamera(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            return;
        }

        storedCall = call;
        videoDuration = call.getInt("duration", 0);
        videoFrameRate = call.getInt("frameRate", 120);
        videoSizeLimit = call.getInt("sizeLimit", 0);

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
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No back camera found");
    }

    @PermissionCallback
    private void onCameraPermissionResult(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            openCamera(call);
        } else {
            call.reject("Permission denied");
        }
    }

    @SuppressLint("MissingPermission")
    private void openNativeCamera() throws CameraAccessException {
        cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice camera) {
                cameraDevice = camera;
                try {
                    setupMediaRecorder();
                    startCaptureSession();
                } catch (Exception e) {
                    storedCall.reject("Camera session error: " + e.getMessage());
                }
            }

            public void onDisconnected(CameraDevice camera) {
                camera.close();
            }

            public void onError(CameraDevice camera, int error) {
                camera.close();
            }
        }, backgroundHandler);

        showControlButton();
    }

    private void showControlButton() {
        Activity activity = getActivity();
        Button recordButton = new Button(activity);
        recordButton.setText("▶ Start");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, 0, 0, 120);
        recordButton.setLayoutParams(lp);

        recordButton.setOnClickListener(v -> {
            try {
                if (!isRecording) {
                    mediaRecorder.start();
                    isRecording = true;
                    recordButton.setText("⏹ Stop");
                } else {
                    isRecording = false;
                    recordButton.setEnabled(false);
                    stopNativeRecording();
                }
            } catch (Exception e) {
                storedCall.reject("Button error: " + e.getMessage());
            }
        });

        activity.runOnUiThread(() -> {
            FrameLayout root = activity.findViewById(android.R.id.content);
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            overlay.addView(recordButton);
            root.addView(overlay);
        });
    }

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
        mediaRecorder.prepare();
        Thread.sleep(500);
    }

    private void startCaptureSession() throws CameraAccessException {
        Surface recorderSurface = mediaRecorder.getSurface();

        cameraDevice.createConstrainedHighSpeedCaptureSession(
                Arrays.asList(recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            CameraConstrainedHighSpeedCaptureSession hsSession = (CameraConstrainedHighSpeedCaptureSession) session;
                            CaptureRequest.Builder builder = cameraDevice
                                    .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            builder.addTarget(recorderSurface);
                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(120, 120));

                            hsSession.setRepeatingBurst(
                                    hsSession.createHighSpeedRequestList(builder.build()),
                                    null, backgroundHandler);
                        } catch (Exception e) {
                            storedCall.reject("Capture failed: " + e.getMessage());
                        }
                    }

                    public void onConfigureFailed(CameraCaptureSession session) {
                        storedCall.reject("Configuration failed");
                    }
                },
                backgroundHandler);
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

            JSObject ret = new JSObject();
            ret.put("videoPath", videoPath);
            storedCall.resolve(ret);
        } catch (Exception e) {
            storedCall.reject("Stop failed: " + e.getMessage());
        }
    }
}
