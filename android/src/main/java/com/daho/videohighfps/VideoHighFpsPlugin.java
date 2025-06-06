// ✅ your package declaration remains the same
package com.daho.videohighfps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import android.media.MediaScannerConnection;

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

    private TextureView textureView;
    private Surface previewSurface;
    private Size selectedSize = new Size(1280, 720);
    private String selectedCameraId = null;

    private int videoDuration = 0;
    private int videoFrameRate = 30;
    private int videoSizeLimit = 0;
    private String videoQuality = "hd";
    private boolean isRecording = false;
    private PluginCall storedCall;

    @PluginMethod
    public void openCamera(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            Log.d("VideoHighFps", "❌-=====> open camera, permission denied");
            return;
        }

        Log.d("VideoHighFps", "✅-=====> open camera, permission grated");

        storedCall = call;
        videoDuration = safeGetInt(call, "duration", 0);
        videoFrameRate = safeGetInt(call, "frameRate", 30);
        videoSizeLimit = safeGetInt(call, "sizeLimit", 0);
        videoQuality = call.getString("quality", "hd");

        Context context = getContext();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        HandlerThread handlerThread = new HandlerThread("CameraBackground");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());

        try {
            selectedCameraId = getBackCameraId();
        } catch (Exception e) {
            call.reject("No valid back-facing camera found: " + e.getMessage());
            return;
        }

        Activity activity = getActivity();
        textureView = new TextureView(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);

        activity.runOnUiThread(() -> {
            FrameLayout root = activity.findViewById(android.R.id.content);
            FrameLayout backgroundLayer = new FrameLayout(activity);
            backgroundLayer.setLayoutParams(params);

            textureView.setLayoutParams(params);
            backgroundLayer.addView(textureView);

            Log.d("VideoHighFps", "✅ 1 -=====> addControlButton");
            root.addView(backgroundLayer);
            backgroundLayer.post(() -> addControlButton(backgroundLayer));
            Log.d("VideoHighFps", "✅ 4 -=====> addControlButton");
        });

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            boolean isReady = false;

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (!isReady) {
                    isReady = true;
                    previewSurface = new Surface(surface);
                    try {
                        selectResolutionBasedOnQuality();
                        openNativeCamera();
                    } catch (Exception e) {
                        call.reject("Failed selecting camera size: " + e.getMessage());
                    }
                }
            }

            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    private void addControlButton(FrameLayout parent) {
        Activity activity = getActivity();
        Button recordButton = new Button(activity);
        recordButton.setText("▶ Start");

        Log.d("VideoHighFps", "✅ 2 -=====> addControlButton");

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
                Log.e("VideoHighFpsPlugin", "Recording toggle failed: " + e.getMessage());
                storedCall.reject("Recording error: " + e.getMessage());
            }
        });

        parent.addView(recordButton);
        Log.d("VideoHighFps", "✅ 3 -=====>3 addControlButton");
    }

    @PermissionCallback
    private void onCameraPermissionResult(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            Log.d("VideoHighFps", "✅-=====> permission granted");
            openCamera(call);
        } else {
            Log.d("VideoHighFps", "❌-=====> permission not granted");
            call.reject("Permission not granted.");
        }
    }

    private void selectResolutionBasedOnQuality() throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] supportedSizes = map.getOutputSizes(MediaRecorder.class);

        switch (videoQuality) {
            case "uhd":
                selectedSize = findMatchingSize(supportedSizes, 3840, 2160);
                break;
            case "fhd":
                selectedSize = findMatchingSize(supportedSizes, 1920, 1080);
                break;
            default:
                selectedSize = findMatchingSize(supportedSizes, 1280, 720);
                break;
        }
    }

    private Size findMatchingSize(Size[] sizes, int width, int height) {
        for (Size s : sizes)
            if (s.getWidth() >= width && s.getHeight() >= height)
                return s;
        return sizes[0];
    }

    private String getBackCameraId() throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)
                return cameraId;
        }
        throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Back-facing camera not found");
    }

    @SuppressLint("MissingPermission")
    private void openNativeCamera() {
        try {
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        startCaptureSession();
                    } catch (Exception e) {
                        storedCall.reject("Capture session failed: " + e.getMessage());
                    }
                }

                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);
        } catch (Exception e) {
            storedCall.reject("Camera open failed: " + e.getMessage());
        }
    }

    private void startCaptureSession() throws Exception {
        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();

        cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            CaptureRequest.Builder builder = cameraDevice
                                    .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            builder.addTarget(previewSurface);
                            builder.addTarget(recorderSurface);
                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    new Range<>(videoFrameRate, videoFrameRate));
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        } catch (Exception e) {
                            storedCall.reject("Failed to start preview: " + e.getMessage());
                        }
                    }

                    public void onConfigureFailed(CameraCaptureSession session) {
                        storedCall.reject("Camera configuration failed");
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

            if (textureView != null && textureView.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) textureView.getParent();
                ViewGroup root = (ViewGroup) parent.getParent();
                if (root != null) {
                    root.removeView(parent); // removes full overlay frame
                }
            }

            // ✅ Scan the saved video to make it visible in the Gallery
            if (videoPath != null) {
                MediaScannerConnection.scanFile(getContext(),
                        new String[] { videoPath },
                        new String[] { "video/mp4" },
                        null);
            }

            // ✅ Send result back to Capacitor
            JSObject ret = new JSObject();
            ret.put("videoPath", videoPath);
            storedCall.resolve(ret);

        } catch (Exception e) {
            storedCall.reject("Error stopping recording: " + e.getMessage());
        }
    }

    private int safeGetInt(PluginCall call, String key, int defaultValue) {
        Integer value = call.getInt(key, defaultValue);
        return (value != null) ? value : defaultValue;
    }

    private int getOrientationHint() throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(selectedCameraId);
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        return (sensorOrientation - degrees + 360) % 360;
    }

    private void setupMediaRecorder() throws Exception {
        File outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);

        String fileName = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        videoPath = new File(outputDir, fileName).getAbsolutePath();
        Log.d("VideoHighFps -=>", "✅ Saved videoPath: " + videoPath);

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
        mediaRecorder.setOrientationHint(getOrientationHint());

        if (videoDuration > 0)
            mediaRecorder.setMaxDuration(videoDuration * 1000);
        if (videoSizeLimit > 0)
            mediaRecorder.setMaxFileSize(videoSizeLimit);

        mediaRecorder.prepare();
        Thread.sleep(500); // Add this line
    }
}
