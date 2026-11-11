package com.ytliquid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * The MIUI-style selection pill drawn behind the active tab.
 *
 * <p>It lives in the bar's {@link android.graphics.drawable.LayerDrawable}
 * background, so it is painted behind the bar's children (icons and labels)
 * but above the blurred backdrop.
 */
public class PillDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float radius;
    private int color = 0xFF2F7BFF;

    public PillDrawable() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void setColor(int color) {
        if (this.color != color) {
            this.color = color;
            invalidateSelf();
        }
    }

    public boolean setItemBounds(Rect bounds, float radius) {
        if (this.rect.equals(bounds) && this.radius == radius) return false;
        this.rect.set(bounds);
        this.radius = radius;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
