package com.daho.videohighfps.lib.onnx;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

public class LightingAnalyzer {

    private static final String TAG = "✅ LightingAnalyzer";

    /**
     * Checks if lighting is sufficient based on average luminance of the image.
     */
    public boolean isLightingGood(Bitmap bitmap) {
        float averageLuminance = getAverageLuminance(bitmap);
        Log.d(TAG, "💡 Average luminance: " + averageLuminance);
        return averageLuminance >= 40; // You can adjust this threshold
    }

    /**
     * Calculates average luminance (brightness) from a bitmap.
     */
    public float getAverageLuminance(Bitmap bitmap) {
        if (bitmap == null)
            return 0;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampleSize = 10; // Reduce number of pixels for performance
        int total = 0;
        int count = 0;

        for (int x = 0; x < width; x += sampleSize) {
            for (int y = 0; y < height; y += sampleSize) {
                int color = bitmap.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int luminance = (int) (0.2126 * r + 0.7152 * g + 0.0722 * b);
                total += luminance;
                count++;
            }
        }

        return count == 0 ? 0 : (float) total / count;
    }
}
