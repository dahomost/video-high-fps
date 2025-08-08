package com.daho.videohighfps.lib.onnx;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
public class FeedbackHelper {

    private static final String TAG = "✅ FeedbackHelper";

    private final Context context;
    private TextToSpeech tts;
    private boolean isTtsReady = false;
    private boolean isSpeaking = false;

    public FeedbackHelper(Context context) {
        this.context = context;
        initTTS();
    }

    private void initTTS() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int langResult = tts.setLanguage(Locale.US);
                isTtsReady = (langResult != TextToSpeech.LANG_MISSING_DATA &&
                        langResult != TextToSpeech.LANG_NOT_SUPPORTED);
                Log.d(TAG, "🔊 TTS initialized: " + isTtsReady);
            } else {
                Log.e(TAG, "❌ Failed to initialize TTS");
            }
        });
    }

    public void speak(String text) {
        if (isTtsReady && !isSpeaking) {
            Log.d(TAG, "🗣️ Speaking: " + text);
            isSpeaking = true;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback_tts");

            tts.setOnUtteranceProgressListener(new TextToSpeech.UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onError(String utteranceId) {
                    isSpeaking = false;
                    Log.e(TAG, "❌ TTS error");
                }

                @Override
                public void onDone(String utteranceId) {
                    isSpeaking = false;
                    Log.d(TAG, "✅ TTS done");
                }
            });
        }
    }

    public void beep(int durationMs) {
        new Thread(() -> {
            try {
                ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs);
                Log.d(TAG, "📢 Beep played (" + durationMs + "ms)");
            } catch (Exception e) {
                Log.e(TAG, "⚠️ Beep failed", e);
            }
        }).start();
    }

    public void shutdown() {
        if (tts != null) {
            Log.d(TAG, "🛑 Shutting down TTS");
            tts.shutdown();
            tts = null;
            isTtsReady = false;
        }
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public boolean isTtsReady() {
        return isTtsReady;
    }
}
