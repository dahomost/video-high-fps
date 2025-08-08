package com.daho.videohighfps.lib.recording;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingController {

    private static final String TAG = "✅ RecordingController";

    private final Context context;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;

    public RecordingController(Context context) {
        this.context = context;
    }

    public void startRecording(String outputPath, Size videoSize, int frameRate, int bitrate) {
        try {
            Log.d(TAG, "📸 Initializing MediaRecorder");
            mediaRecorder = new MediaRecorder();

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(outputPath);
            mediaRecorder.setVideoEncodingBitRate(bitrate);
            mediaRecorder.setVideoFrameRate(frameRate);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            Log.d(TAG, "🎬 Recording started");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start recording", e);
            isRecording = false;
        }
    }

    public void stopRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                Log.d(TAG, "🛑 Stopping MediaRecorder");
                mediaRecorder.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Error stopping recording", e);
        } finally {
            safeReleaseMediaRecorder();
        }
    }

    public void pauseRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                Log.d(TAG, "⏸️ Pausing recording");
                mediaRecorder.pause();
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Error pausing recording", e);
        }
    }

    public void resumeRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                Log.d(TAG, "▶️ Resuming recording");
                mediaRecorder.resume();
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Error resuming recording", e);
        }
    }

    public void cancelRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                Log.w(TAG, "❌ Canceling recording");
                mediaRecorder.stop();
                mediaRecorder.reset();
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Error cancelling recording", e);
        } finally {
            safeReleaseMediaRecorder();
        }
    }

    public void safeReleaseMediaRecorder() {
        Log.d(TAG, "🎞️ Releasing MediaRecorder");
        try {
            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ Error releasing MediaRecorder", e);
        } finally {
            mediaRecorder = null;
            isRecording = false;
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public int calculateBitrate(Size resolution, int fps) {
        int pixels = resolution.getWidth() * resolution.getHeight();
        int bitrate = pixels * fps;
        int averageBitrate = bitrate * 4;
        Log.d(TAG, "📐 Calculated bitrate: " + averageBitrate);
        return averageBitrate;
    }

    public String generateOutputFilePath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(Environment.getExternalStorageDirectory(), "tpa-videos");
        if (!dir.exists())
            dir.mkdirs();
        return new File(dir, "VID_" + timestamp + ".mp4").getAbsolutePath();
    }
}
