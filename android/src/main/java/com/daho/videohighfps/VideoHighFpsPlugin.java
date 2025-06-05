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
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
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
    private String selectedCameraId = null; // ✅ hold valid cameraId

    private int videoDuration = 0;
    private int videoFrameRate = 30;
    private int videoSizeLimit = 0;
    private String videoQuality = "hd";

    @PluginMethod
    public void startRecording(PluginCall call) {
        Log.d("VideoHighFpsPlugin", "startRecording() invoked");

        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "onCameraPermissionResult");
            return;
        }

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
            selectedCameraId = getBackCameraId(); // ✅ select valid back-facing camera
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
            root.addView(backgroundLayer, 0);
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
                        openCamera(call);
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

    @PermissionCallback
    private void onCameraPermissionResult(PluginCall call, String permission) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            startRecording(call);
        } else {
            call.reject("Permission not granted");
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
        for (Size s : sizes) {
            if (s.getWidth() >= width && s.getHeight() >= height)
                return s;
        }
        return sizes[0];
    }

    // ✅ Picks the first back-facing camera
    private String getBackCameraId() throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Back-facing camera not found");
    }

    @SuppressLint("MissingPermission")
    private void openCamera(PluginCall call) {
        try {
            cameraManager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        startCaptureSession(call);
                    } catch (Exception e) {
                        call.reject("Capture session failed: " + e.getMessage());
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
            call.reject("Camera open failed: " + e.getMessage());
        }
    }

    private void startCaptureSession(PluginCall call) throws Exception {
        setupMediaRecorder();
        Surface recorderSurface = mediaRecorder.getSurface();

        cameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, recorderSurface),
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
                            mediaRecorder.start();
                        } catch (Exception e) {
                            call.reject("Failed to start recording: " + e.getMessage());
                        }
                    }

                    public void onConfigureFailed(CameraCaptureSession session) {
                        call.reject("Camera configuration failed");
                    }
                }, backgroundHandler);
    }

    private int safeGetInt(PluginCall call, String key, int defaultValue) {
        Integer value = call.getInt(key, defaultValue);
        return (value != null) ? value : defaultValue;
    }

    private void setupMediaRecorder() throws Exception {
        File outputDir = getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
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

        if (videoDuration > 0)
            mediaRecorder.setMaxDuration(videoDuration * 1000);
        if (videoSizeLimit > 0)
            mediaRecorder.setMaxFileSize(videoSizeLimit);

        mediaRecorder.prepare();
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (RuntimeException e) {
                    File file = new File(videoPath);
                    if (!file.delete()) {
                        Log.w("VideoHighFpsPlugin", "Failed to delete corrupted video file: " + videoPath);
                    }
                    throw e;
                }
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (textureView != null && textureView.getParent() != null) {
                ((ViewGroup) textureView.getParent()).removeView(textureView);
                textureView = null;
            }

            JSObject ret = new JSObject();
            ret.put("videoPath", videoPath);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error stopping recording: " + e.getMessage());
        }
    }
}
