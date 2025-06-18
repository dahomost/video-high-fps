package com.daho.videohighfps;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class FeedbackHelper {

    private TextToSpeech tts;
    private final Context context;
    private boolean ttsReady = false;
    private static final String TAG = "FeedbackHelper";

    public FeedbackHelper(Context context) {
        this.context = context;

        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                ttsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED);
                Log.d(TAG, "TTS initialized, ready: " + ttsReady);
            } else {
                Log.e(TAG, "TTS initialization failed");
            }
        });
    }

    public void speakWithBeeps(String message, int beepCount) {
        playBeeps(beepCount);

        if (ttsReady) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ttsId");
        } else {
            Log.w(TAG, "TTS not ready, message skipped: " + message);
        }
    }

    private void playBeeps(int count) {
        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        for (int i = 0; i < count; i++) {
            toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 150);
            try {
                Thread.sleep(250); // gap between beeps
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        toneGen.release();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
