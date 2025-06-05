package com.daho.videohighfps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

@CapacitorPlugin(name = "VideoHighFps", permissions = {
        @Permission(strings = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, alias = "camera"),
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

    private int videoDuration = 0;
    private int videoFrameRate = 30;
    private int videoSizeLimit = 0;
    private String videoQuality = "hd";

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "startRecording");
            return;
        }

        try {
            videoDuration = call.getInt("duration", 0);
            videoFrameRate = call.getInt("frameRate", 30);
            videoSizeLimit = call.getInt("sizeLimit", 0);
            videoQuality = call.getString("quality", "hd");

            Context context = getContext();
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            HandlerThread handlerThread = new HandlerThread("CameraBackground");
            handlerThread.start();
            backgroundHandler = new Handler(handlerThread.getLooper());

            Activity activity = getActivity();
            textureView = new TextureView(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);

            activity.runOnUiThread(() -> activity.addContentView(textureView, params));

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    previewSurface = new Surface(surface);
                    try {
                        selectResolutionBasedOnQuality();
                        openCamera(call);
                    } catch (Exception e) {
                        call.reject("Failed selecting camera size: " + e.getMessage());
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });

        } catch (Exception e) {
            call.reject("Error initializing recording: " + e.getMessage());
        }
    }

    private void selectResolutionBasedOnQuality() throws CameraAccessException {
        String cameraId = cameraManager.getCameraIdList()[0];
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] supportedSizes = map.getOutputSizes(MediaRecorder.class);

        switch (videoQuality) {
            case "uhd":
                selectedSize = findMatchingSize(supportedSizes, 3840, 2160);
                break;
            case "fhd":
                selectedSize = findMatchingSize(supportedSizes, 1920, 1080);
                break;
            case "hd":
            default:
                selectedSize = findMatchingSize(supportedSizes, 1280, 720);
                break;
        }
    }

    private Size findMatchingSize(Size[] sizes, int width, int height) {
        for (Size s : sizes) {
            if (s.getWidth() >= width && s.getHeight() >= height) {
                return s;
            }
        }
        return sizes[0];
    }

    @SuppressLint("MissingPermission")
    private void openCamera(PluginCall call) {
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        startCaptureSession(call);
                    } catch (Exception e) {
                        call.reject("Capture session failed: " + e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
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
                    @Override
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

                            JSObject ret = new JSObject();
                            ret.put("path", videoPath);
                            call.resolve(ret);
                        } catch (Exception e) {
                            call.reject("Failed to start recording: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        call.reject("Camera configuration failed");
                    }
                },
                backgroundHandler);
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

        if (videoDuration > 0) {
            mediaRecorder.setMaxDuration(videoDuration * 1000);
        }

        if (videoSizeLimit > 0) {
            mediaRecorder.setMaxFileSize(videoSizeLimit);
        }

        mediaRecorder.prepare();
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
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
