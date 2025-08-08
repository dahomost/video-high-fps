package com.daho.videohighfps.lib.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

import com.daho.videohighfps.R;

public class UIOverlayManager {

    private static final String TAG = "✅ UIOverlayManager";

    private final Activity activity;
    private final FrameLayout overlay;
    private final TextureView textureView;

    public UIOverlayManager(Activity activity, FrameLayout overlay, TextureView textureView) {
        this.activity = activity;
        this.overlay = overlay;
        this.textureView = textureView;
    }

    public void setupUI(View blackOverlayView) {
        Log.d(TAG, "🖥️ Setting up UI overlay");

        activity.runOnUiThread(() -> {
            if (blackOverlayView != null) {
                overlay.addView(blackOverlayView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            if (textureView != null) {
                textureView.setAlpha(0f); // Invisible initially
                overlay.addView(textureView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        });
    }

    public void showCameraPreview() {
        activity.runOnUiThread(() -> {
            if (textureView != null) {
                fadeTo(textureView, 1f, 400);
            }
        });
    }

    public Button createIconButton(@DrawableRes int drawableId) {
        Button button = new Button(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackground(ContextCompat.getDrawable(activity, drawableId));
        } else {
            button.setBackgroundResource(drawableId);
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dpToPx(activity, 48),
                dpToPx(activity, 48));
        params.gravity = Gravity.CENTER;
        button.setLayoutParams(params);

        return button;
    }

    public void fadeTo(View view, float targetAlpha, int duration) {
        AlphaAnimation animation = new AlphaAnimation(view.getAlpha(), targetAlpha);
        animation.setDuration(duration);
        animation.setFillAfter(true);
        view.startAnimation(animation);
    }

    public int dpToPx(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    // Additional UI logic can be added here
}
