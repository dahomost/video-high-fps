package com.daho.videohighfps;

import android.content.Context;
import com.daho.videohighfps.FeedbackHelper;

public class onnxPreChecking {

    private final FeedbackHelper feedbackHelper;

    public onnxPreChecking(Context context) {
        this.feedbackHelper = new FeedbackHelper(context);
    }

    public void sayTooDarkWarning() {
        feedbackHelper.speakWithBeeps("Too dark", 3);
    }

    public void sayMoveBackWarning() {
        feedbackHelper.speakWithBeeps("Move back", 2);
    }

    public void sayCenterYourselfWarning() {
        feedbackHelper.speakWithBeeps("Center yourself", 2);
    }

    public void cleanup() {
        feedbackHelper.shutdown();
    }
}
