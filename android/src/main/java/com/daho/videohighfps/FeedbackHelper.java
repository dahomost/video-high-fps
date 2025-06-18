package com.daho.videohighfps;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.os.Handler;
import java.util.Locale;

public class FeedbackHelper {

    private TextToSpeech tts;
    private final Context context;
    private boolean ttsReady = false;
    private static final String TAG = "ONNX";

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

    public void speakWithBeeps(String message, int beepCount, long delayAfterTtsMs, Runnable afterTtsAction) {
        if (ttsReady) {
            new Handler(Looper.getMainLooper()).post(() -> {
                playBeeps(beepCount);

                // Delay after beeps before speaking
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Speaking via TTS: " + message);
                    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ttsId");

                    // Estimate duration and post callback
                    int estimatedTtsDuration = message.length() * 50; // ~50ms per character
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (afterTtsAction != null) {
                            afterTtsAction.run();
                        }
                    }, estimatedTtsDuration + delayAfterTtsMs);

                }, 300); // Delay after beep before TTS
            });
        } else {
            Log.w(TAG, "TTS not ready, skipping speech: " + message);
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
