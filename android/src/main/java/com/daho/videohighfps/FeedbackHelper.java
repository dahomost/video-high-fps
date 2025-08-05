package com.daho.videohighfps; // Defines the package where this class belongs

import android.content.Context; // For accessing application-specific resources
import android.media.AudioManager; // Manages audio streams
import android.media.ToneGenerator; // Generates beep tones
import android.os.Handler; // For scheduling tasks on threads
import android.os.Looper; // Access to the main (UI) thread looper
import android.speech.tts.TextToSpeech; // For Text-to-Speech functionality
import android.speech.tts.UtteranceProgressListener; // Listener for TTS progress
import android.util.Log; // Logging utility

import java.util.Locale; // For language settings
import java.util.concurrent.atomic.AtomicBoolean; // Thread-safe boolean
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FeedbackHelper {
    private static final String TAG = "ONNX"; // Log tag for debugging

    private TextToSpeech tts; // Instance of the Text-to-Speech engine
    private final Context context; // Application context (used for initializing TTS)
    private final AtomicBoolean isTtsReady = new AtomicBoolean(false); // Flag: is TTS engine initialized?
    private final AtomicBoolean isSpeaking = new AtomicBoolean(false); // Flag: is TTS currently speaking?
    private final Handler mainHandler = new Handler(Looper.getMainLooper()); // Runs code on the main (UI) thread
    private final Runnable ttsTimeoutRunnable = this::handleTtsInitTimeout; // Action to run if TTS takes too long to
                                                                            // init

    // Constants for beep sound configuration
    private static final int BEEP_VOLUME = 50; // Volume of the beep (0–100)
    private static final int BEEP_DURATION_MS = 150; // Length of each beep in milliseconds
    private static final int BEEP_INTERVAL_MS = 500; // Delay between beeps in milliseconds
    private static final int TTS_INIT_TIMEOUT_MS = 5000; // Max time to wait for TTS to initialize

    private final ExecutorService beepExecutor = Executors.newSingleThreadExecutor();

    public FeedbackHelper(Context context) {
        this.context = context; // Save context
        initializeTts(); // Start TTS engine setup
    }

    /**
     * Initializes the Text-to-Speech engine with a timeout to avoid hanging.
     * Runs on the main thread to ensure UI compatibility.
     */
    private void initializeTts() {
        // Run on main thread to avoid UI issues (UI thread required for TTS)
        mainHandler.post(() -> {
            try {
                // Create TTS instance with callback
                tts = new TextToSpeech(context, status -> {
                    mainHandler.removeCallbacks(ttsTimeoutRunnable); // Cancel timeout check

                    if (status == TextToSpeech.SUCCESS) {
                        // Try to set US English as language
                        int result = tts.setLanguage(Locale.US);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.e(TAG, "TTS Language not supported");
                        } else {
                            isTtsReady.set(true); // TTS is ready
                            setupTtsListeners(); // Setup callbacks for TTS events
                            Log.d(TAG, "TTS initialized successfully");
                        }
                    } else {
                        Log.e(TAG, "TTS initialization failed with status: " + status);
                    }
                });

                // Set a timeout in case TTS hangs during init
                mainHandler.postDelayed(ttsTimeoutRunnable, TTS_INIT_TIMEOUT_MS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize TTS", e);
            }
        });
    }

    /**
     * Sets up listeners for TTS events to track speaking state.
     * This allows us to know when TTS starts, finishes, or encounters an error.
     * onStart → When TTS starts speaking — tts.speak(...) begins
     * onDone → When TTS finishes speaking — auto-triggered after full playback
     * onError → If TTS fails to speak — triggered during/after tts.speak(...)
     * failure
     */
    private void setupTtsListeners() {
        // Listen for start, done, and error events during TTS playback
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "TTS started speaking");
            }

            @Override
            public void onDone(String utteranceId) {
                isSpeaking.set(false); // Mark speaking as done
                Log.d(TAG, "TTS finished speaking");
            }

            @Override
            public void onError(String utteranceId) {
                isSpeaking.set(false); // Allow new speech
                Log.e(TAG, "TTS error occurred");
            }
        });
    }

    private void handleTtsInitTimeout() {
        // If TTS is not ready within timeout period
        if (!isTtsReady.get()) {
            Log.e(TAG, "TTS initialization timed out");
            if (tts != null) {
                tts.shutdown(); // Shut it down
                tts = null; // Clear reference
            }
        }
    }

    public void speakWithBeeps(String message, int beepCount, long delayAfterTtsMs, Runnable afterTtsAction) {
        // Skip empty messages
        if (message == null || message.isEmpty()) {
            Log.w(TAG, "Empty message provided");
            return;
        }

        // Skip if TTS not ready or already speaking
        if (!isTtsReady.get() || isSpeaking.getAndSet(true)) {
            Log.w(TAG, "TTS not ready or already speaking - Skipping: " + message);
            return;
        }

        // Play beeps first, then speak
        playBeepsAsync(beepCount, () -> {
            if (!isTtsReady.get()) {
                isSpeaking.set(false);
                return;
            }

            // Delay after beep before speaking
            mainHandler.postDelayed(() -> {
                try {
                    Log.d(TAG, "Speaking: " + message);
                    // Speak message immediately (flush any queue)
                    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "tts_" + System.currentTimeMillis());

                    // Estimate speaking duration using word count
                    int wordCount = message.split("\\s+").length;
                    long estimatedDuration = Math.max(1000, wordCount * 150); // Minimum 1s, else 150ms/word

                    // After estimated time, run follow-up action (if any)
                    mainHandler.postDelayed(() -> {
                        if (afterTtsAction != null) {
                            afterTtsAction.run();
                        }
                    }, estimatedDuration + delayAfterTtsMs);
                } catch (Exception e) {
                    Log.e(TAG, "Error during TTS speak", e);
                    isSpeaking.set(false); // Allow future TTS
                }
            }, 300); // Delay after beeps before TTS starts
        });
    }

    /**
     * Plays a series of beep sounds asynchronously.
     * Runs in a background thread to avoid blocking the main thread.
     * 
     * @param count      Number of beeps to play
     * @param onComplete Action to run after all beeps finish
     */
    private void playBeepsAsync(int count, Runnable onComplete) {
        if (count <= 0) {
            mainHandler.post(onComplete);
            return;
        }

        // ✅ Use safe, reusable background thread
        beepExecutor.execute(() -> {
            ToneGenerator toneGen = null;
            try {
                toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME);
                for (int i = 0; i < count; i++) {
                    toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, BEEP_DURATION_MS);
                    Thread.sleep(BEEP_INTERVAL_MS);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during beep generation", e);
            } finally {
                if (toneGen != null) {
                    toneGen.release();
                }
                mainHandler.post(onComplete); // 🔄 back to main thread
            }
        });
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        isSpeaking.set(false);

        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down TTS", e);
            }
            tts = null;
        }

        beepExecutor.shutdownNow(); // ✅ CORRECT placement
        isTtsReady.set(false);
    }
}