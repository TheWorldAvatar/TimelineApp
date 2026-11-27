package uk.ac.cam.cares.jps.user.exposure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.WeekPoint;

public class WeeklyTrendChartView extends View {

    public interface OnWeekSelectedListener {
        void onWeekSelected(int index, WeekPoint point);
    }

    private List<WeekPoint> points = Collections.emptyList();
    private int selectedIndex = -1;
    private boolean showRangeBand = true;
    private OnWeekSelectedListener listener;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path areaPath = new Path();
    private final Path bandPath = new Path();

    private int primaryColor;
    private int surfaceColor;
    private int mutedTextColor;

    public WeeklyTrendChartView(Context context) {
        super(context);
        init();
    }

    public WeeklyTrendChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WeeklyTrendChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        primaryColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF00687C);
        surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        mutedTextColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF40484B);
        int outlineColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0xFFBFC8CB);

        gridPaint.setColor(outlineColor);
        gridPaint.setAlpha(110);
        gridPaint.setStrokeWidth(dp(1f));

        axisTextPaint.setColor(mutedTextColor);
        axisTextPaint.setTextSize(sp(11f));

        linePaint.setColor(primaryColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        bandPaint.setColor(primaryColor);
        bandPaint.setAlpha(36);
        bandPaint.setStyle(Paint.Style.FILL);

        nodeFillPaint.setColor(surfaceColor);
        nodeFillPaint.setStyle(Paint.Style.FILL);

        nodeRingPaint.setColor(primaryColor);
        nodeRingPaint.setStyle(Paint.Style.STROKE);
        nodeRingPaint.setStrokeWidth(dp(2.2f));

        crosshairPaint.setColor(primaryColor);
        crosshairPaint.setAlpha(160);
        crosshairPaint.setStrokeWidth(dp(1.2f));
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{dp(4f), dp(4f)}, 0));
    }

    public void setData(List<WeekPoint> newPoints, int newSelectedIndex, boolean showBand) {
        this.points = newPoints != null ? newPoints : Collections.emptyList();
        this.selectedIndex = newSelectedIndex;
        this.showRangeBand = showBand;
        invalidate();
    }

    public void setOnWeekSelectedListener(@Nullable OnWeekSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;

        float padLeft = dp(48f);
        float padRight = dp(16f);
        float padTop = dp(18f);
        float padBottom = dp(28f);

        float plotW = Math.max(1f, getWidth() - padLeft - padRight);
        float plotH = Math.max(1f, getHeight() - padTop - padBottom);

        double rawMin = Double.MAX_VALUE;
        double rawMax = -Double.MAX_VALUE;
        for (WeekPoint p : points) {
            rawMin = Math.min(rawMin, showRangeBand ? p.minValue : p.displayValue);
            rawMax = Math.max(rawMax, showRangeBand ? p.maxValue : p.displayValue);
        }
        if (rawMin == rawMax) {
            rawMin = Math.max(0.0, rawMin - 1.0);
            rawMax += 1.0;
        }
        double span = rawMax - rawMin;
        double yMin = Math.max(0.0, rawMin - span * 0.12);
        double yMax = rawMax + span * 0.14;

        // 4 horizontal gridlines + Y-axis labels
        axisTextPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float frac = i / 4f;
            float y = padTop + plotH - frac * plotH;
            double val = yMin + frac * (yMax - yMin);
            canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint);
            canvas.drawText(formatTick(val), padLeft - dp(8f), y + dp(4f), axisTextPaint);
        }

        // Min–Max shaded range band
        if (showRangeBand && points.size() > 1) {
            bandPath.reset();
            for (int i = 0; i < points.size(); i++) {
                float x = xFor(i, padLeft, plotW);
                float yTop = yFor(points.get(i).maxValue, yMin, yMax, padTop, plotH);
                if (i == 0) bandPath.moveTo(x, yTop);
                else bandPath.lineTo(x, yTop);
            }
            for (int i = points.size() - 1; i >= 0; i--) {
                float x = xFor(i, padLeft, plotW);
                float yBot = yFor(points.get(i).minValue, yMin, yMax, padTop, plotH);
                bandPath.lineTo(x, yBot);
            }
            bandPath.close();
            canvas.drawPath(bandPath, bandPaint);
        }

        // Primary curve + gradient fill
        linePath.reset();
        areaPath.reset();
        for (int i = 0; i < points.size(); i++) {
            float x = xFor(i, padLeft, plotW);
            float y = yFor(points.get(i).displayValue, yMin, yMax, padTop, plotH);
            if (i == 0) {
                linePath.moveTo(x, y);
                areaPath.moveTo(x, padTop + plotH);
                areaPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                areaPath.lineTo(x, y);
            }
        }
        if (points.size() > 1) {
            float lastX = xFor(points.size() - 1, padLeft, plotW);
            areaPath.lineTo(lastX, padTop + plotH);
            areaPath.close();

            int startAlphaColor = Color.argb(65, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor));
            int endAlphaColor = Color.argb(4, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor));
            fillPaint.setShader(new LinearGradient(0, padTop, 0, padTop + plotH,
                    startAlphaColor, endAlphaColor, Shader.TileMode.CLAMP));
            canvas.drawPath(areaPath, fillPaint);
        }
        canvas.drawPath(linePath, linePaint);

        // Crosshair on selected week
        if (selectedIndex >= 0 && selectedIndex < points.size()) {
            float selX = xFor(selectedIndex, padLeft, plotW);
            canvas.drawLine(selX, padTop, selX, padTop + plotH, crosshairPaint);
        }

        // X-axis week labels + nodes
        axisTextPaint.setTextAlign(Paint.Align.CENTER);
        int step = Math.max(1, points.size() / 6);
        for (int i = 0; i < points.size(); i++) {
            float x = xFor(i, padLeft, plotW);
            float y = yFor(points.get(i).displayValue, yMin, yMax, padTop, plotH);
            boolean isSelected = (i == selectedIndex);

            if (isSelected || i % step == 0 || i == points.size() - 1) {
                axisTextPaint.setColor(isSelected ? primaryColor : mutedTextColor);
                canvas.drawText(points.get(i).weekLabel, x, getHeight() - dp(6f), axisTextPaint);
            }

            canvas.drawCircle(x, y, isSelected ? dp(5.5f) : dp(3.5f), nodeFillPaint);
            canvas.drawCircle(x, y, isSelected ? dp(5.5f) : dp(3.5f), nodeRingPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (points.isEmpty()) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float padLeft = dp(48f);
            float padRight = dp(16f);
            float plotW = Math.max(1f, getWidth() - padLeft - padRight);
            float rel = Math.max(0f, Math.min(1f, (event.getX() - padLeft) / plotW));
            int idx = points.size() == 1 ? 0 : Math.round(rel * (points.size() - 1));
            if (idx != selectedIndex) {
                selectedIndex = idx;
                invalidate();
                if (listener != null) listener.onWeekSelected(idx, points.get(idx));
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private float xFor(int idx, float padLeft, float plotW) {
        if (points.size() <= 1) return padLeft + plotW / 2f;
        return padLeft + (idx / (float) (points.size() - 1)) * plotW;
    }

    private float yFor(double val, double yMin, double yMax, float padTop, float plotH) {
        float ratio = (float) ((val - yMin) / Math.max(1e-6, yMax - yMin));
        return padTop + plotH - ratio * plotH;
    }

    private String formatTick(double val) {
        if (Math.abs(val) >= 100) return String.format(Locale.getDefault(), "%.0f", val);
        if (Math.abs(val) >= 10) return String.format(Locale.getDefault(), "%.1f", val);
        return String.format(Locale.getDefault(), "%.2f", val);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}