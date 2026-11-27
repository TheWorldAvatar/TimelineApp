package uk.ac.cam.cares.jps.user.exposure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.text.DecimalFormat;
import java.util.ArrayList;
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
    private boolean barMode = false;
    private OnWeekSelectedListener listener;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselineTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path areaPath = new Path();
    private final Path bandPath = new Path();
    private final RectF barRect = new RectF();

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
        primaryColor = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorPrimary, 0xFF00687C);
        surfaceColor = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        mutedTextColor = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF40484B);
        int outlineColor = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOutlineVariant, 0xFFBFC8CB);

        gridPaint.setColor(outlineColor);
        gridPaint.setAlpha(110);
        gridPaint.setStrokeWidth(dp(1f));

        axisTextPaint.setColor(mutedTextColor);
        axisTextPaint.setTextSize(sp(10.5f));

        baselinePaint.setColor(mutedTextColor);
        baselinePaint.setAlpha(170);
        baselinePaint.setStrokeWidth(dp(1.2f));
        baselinePaint.setPathEffect(new DashPathEffect(new float[]{dp(4f), dp(4f)}, 0));

        baselineTextPaint.setColor(mutedTextColor);
        baselineTextPaint.setTextSize(sp(10f));
        baselineTextPaint.setTextAlign(Paint.Align.RIGHT);

        linePaint.setColor(primaryColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.4f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        bandPaint.setColor(primaryColor);
        bandPaint.setAlpha(34);
        bandPaint.setStyle(Paint.Style.FILL);

        barPaint.setStyle(Paint.Style.FILL);

        nodeFillPaint.setColor(surfaceColor);
        nodeFillPaint.setStyle(Paint.Style.FILL);

        nodeHaloPaint.setColor(primaryColor);
        nodeHaloPaint.setAlpha(48);
        nodeHaloPaint.setStyle(Paint.Style.FILL);

        nodeRingPaint.setColor(primaryColor);
        nodeRingPaint.setStyle(Paint.Style.STROKE);
        nodeRingPaint.setStrokeWidth(dp(2.2f));

        crosshairPaint.setColor(primaryColor);
        crosshairPaint.setAlpha(155);
        crosshairPaint.setStrokeWidth(dp(1.2f));
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{dp(3.5f), dp(3.5f)}, 0));
    }

    public void setData(List<WeekPoint> newPoints, int newSelectedIndex,
                        boolean showBand, boolean isBarMode) {
        this.points = newPoints != null ? newPoints : Collections.emptyList();
        this.selectedIndex = newSelectedIndex;
        this.showRangeBand = showBand && !isBarMode;
        this.barMode = isBarMode;
        invalidate();
    }

    public void setOnWeekSelectedListener(@Nullable OnWeekSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;

        float padLeft = dp(44f);
        float padRight = dp(12f);
        float padTop = dp(20f);
        float padBottom = dp(26f);

        float plotW = Math.max(1f, getWidth() - padLeft - padRight);
        float plotH = Math.max(1f, getHeight() - padTop - padBottom);

        double rawMin = Double.MAX_VALUE;
        double rawMax = -Double.MAX_VALUE;
        double sum = 0.0;
        for (WeekPoint p : points) {
            sum += p.displayValue;
            rawMin = Math.min(rawMin, showRangeBand ? p.minValue : p.displayValue);
            rawMax = Math.max(rawMax, showRangeBand ? p.maxValue : p.displayValue);
        }
        double periodAvg = sum / points.size();

        if (rawMin == rawMax) {
            rawMin = Math.max(0.0, rawMin - 1.0);
            rawMax += 1.0;
        }
        double span = rawMax - rawMin;
        double yMin = barMode ? 0.0 : Math.max(0.0, rawMin - span * 0.14);
        double yMax = rawMax + span * 0.18;

        // 4 horizontal gridlines + Y-axis labels
        axisTextPaint.setColor(mutedTextColor);
        axisTextPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 3; i++) {
            float frac = i / 3f;
            float y = padTop + plotH - frac * plotH;
            double val = yMin + frac * (yMax - yMin);
            canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint);
            canvas.drawText(formatTick(val), padLeft - dp(7f), y + dp(3.5f), axisTextPaint);
        }

        // Period average dashed baseline
        if (points.size() > 1) {
            float avgY = yFor(periodAvg, yMin, yMax, padTop, plotH);
            canvas.drawLine(padLeft, avgY, padLeft + plotW, avgY, baselinePaint);
            canvas.drawText("Avg " + formatBaseline(periodAvg),
                    padLeft + plotW, avgY - dp(4f), baselineTextPaint);
        }

        if (barMode) {
            drawBarChart(canvas, padLeft, padTop, plotW, plotH, yMin, yMax);
        } else {
            drawLineChart(canvas, padLeft, padTop, plotW, plotH, yMin, yMax);
        }

        // X-axis week labels
        axisTextPaint.setTextAlign(Paint.Align.CENTER);
        int step = points.size() <= 6 ? 1 : Math.max(1, (int) Math.ceil(points.size() / 5.0));
        for (int i = 0; i < points.size(); i++) {
            boolean isSelected = (i == selectedIndex);
            if (isSelected || i % step == 0 || i == points.size() - 1) {
                float x = xFor(i, padLeft, plotW);
                axisTextPaint.setColor(isSelected ? primaryColor : mutedTextColor);
                canvas.drawText(points.get(i).weekLabel, x, getHeight() - dp(6f), axisTextPaint);
            }
        }
    }

    private void drawLineChart(Canvas canvas, float padLeft, float padTop, float plotW, float plotH,
                               double yMin, double yMax) {
        int n = points.size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        float[] minYs = new float[n];
        float[] maxYs = new float[n];

        for (int i = 0; i < n; i++) {
            xs[i] = xFor(i, padLeft, plotW);
            ys[i] = yFor(points.get(i).displayValue, yMin, yMax, padTop, plotH);
            minYs[i] = yFor(points.get(i).minValue, yMin, yMax, padTop, plotH);
            maxYs[i] = yFor(points.get(i).maxValue, yMin, yMax, padTop, plotH);
        }

        // Min-Max shaded range band
        if (showRangeBand && n > 1) {
            bandPath.reset();
            buildMonotonePath(bandPath, xs, maxYs, false);
            float[] revXs = new float[n];
            float[] revMinYs = new float[n];
            for (int i = 0; i < n; i++) {
                revXs[i] = xs[n - 1 - i];
                revMinYs[i] = minYs[n - 1 - i];
            }
            buildMonotonePath(bandPath, revXs, revMinYs, true);
            bandPath.close();
            canvas.drawPath(bandPath, bandPaint);
        }

        // Smooth monotone primary curve + gradient area fill
        linePath.reset();
        buildMonotonePath(linePath, xs, ys, false);

        if (n > 1) {
            areaPath.set(linePath);
            areaPath.lineTo(xs[n - 1], padTop + plotH);
            areaPath.lineTo(xs[0], padTop + plotH);
            areaPath.close();

            int startAlpha = Color.argb(65, Color.red(primaryColor),
                    Color.green(primaryColor), Color.blue(primaryColor));
            int endAlpha = Color.argb(4, Color.red(primaryColor),
                    Color.green(primaryColor), Color.blue(primaryColor));
            fillPaint.setShader(new LinearGradient(0, padTop, 0, padTop + plotH,
                    startAlpha, endAlpha, Shader.TileMode.CLAMP));
            canvas.drawPath(areaPath, fillPaint);
        }
        canvas.drawPath(linePath, linePaint);

        // Crosshair on selected week
        if (selectedIndex >= 0 && selectedIndex < n) {
            float selX = xs[selectedIndex];
            canvas.drawLine(selX, padTop, selX, padTop + plotH, crosshairPaint);
        }

        // Nodes
        for (int i = 0; i < n; i++) {
            boolean isSelected = (i == selectedIndex);
            if (isSelected) {
                canvas.drawCircle(xs[i], ys[i], dp(9f), nodeHaloPaint);
            }
            float r = isSelected ? dp(5f) : (n > 16 ? dp(2.2f) : dp(3.2f));
            nodeFillPaint.setColor(isSelected ? primaryColor : surfaceColor);
            canvas.drawCircle(xs[i], ys[i], r, nodeFillPaint);
            canvas.drawCircle(xs[i], ys[i], r, nodeRingPaint);
        }
    }

    private void drawBarChart(Canvas canvas, float padLeft, float padTop, float plotW, float plotH,
                              double yMin, double yMax) {
        int n = points.size();
        float slotW = plotW / Math.max(1, n);
        float barW = Math.min(dp(26f), Math.max(dp(6f), slotW * 0.58f));
        float radius = Math.min(dp(5f), barW / 3f);
        float bottom = padTop + plotH;

        for (int i = 0; i < n; i++) {
            float cx = xFor(i, padLeft, plotW);
            float top = Math.min(bottom - dp(3f), yFor(points.get(i).displayValue, yMin, yMax, padTop, plotH));
            boolean isSelected = (i == selectedIndex);
            barPaint.setColor(primaryColor);
            barPaint.setAlpha(isSelected ? 242 : 88);
            barRect.set(cx - barW / 2f, top, cx + barW / 2f, bottom);
            canvas.drawRoundRect(barRect, radius, radius, barPaint);
        }
    }

    private void buildMonotonePath(Path path, float[] xs, float[] ys, boolean appendLine) {
        int n = xs.length;
        if (n == 0) return;
        if (appendLine) {
            path.lineTo(xs[0], ys[0]);
        } else {
            path.moveTo(xs[0], ys[0]);
        }
        if (n == 1) return;
        if (n == 2) {
            path.lineTo(xs[1], ys[1]);
            return;
        }

        float[] dx = new float[n - 1];
        float[] slopes = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            dx[i] = xs[i + 1] - xs[i];
            float dy = ys[i + 1] - ys[i];
            slopes[i] = Math.abs(dx[i]) > 1e-4f ? dy / dx[i] : 0f;
        }

        float[] tangents = new float[n];
        tangents[0] = slopes[0];
        tangents[n - 1] = slopes[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (slopes[i - 1] * slopes[i] <= 0f) {
                tangents[i] = 0f;
            } else {
                float w1 = 2f * dx[i] + dx[i - 1];
                float w2 = dx[i] + 2f * dx[i - 1];
                tangents[i] = (w1 + w2) / (w1 / slopes[i - 1] + w2 / slopes[i]);
            }
        }

        for (int i = 0; i < n - 1; i++) {
            float seg = dx[i] / 3f;
            float cp1x = xs[i] + seg;
            float cp1y = ys[i] + tangents[i] * seg;
            float cp2x = xs[i + 1] - seg;
            float cp2y = ys[i + 1] - tangents[i + 1] * seg;
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, xs[i + 1], ys[i + 1]);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (points.isEmpty()) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float padLeft = dp(44f);
            float padRight = dp(12f);
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
        if (barMode) {
            float slotW = plotW / Math.max(1, points.size());
            return padLeft + idx * slotW + slotW / 2f;
        }
        if (points.size() <= 1) return padLeft + plotW / 2f;
        return padLeft + (idx / (float) (points.size() - 1)) * plotW;
    }

    private float yFor(double val, double yMin, double yMax, float padTop, float plotH) {
        float ratio = (float) ((val - yMin) / Math.max(1e-6, yMax - yMin));
        return padTop + plotH - ratio * plotH;
    }

    private String formatTick(double val) {
        if (Math.abs(val) >= 1000) return String.format(Locale.getDefault(), "%.1fk", val / 1000.0);
        if (Math.abs(val) >= 100) return String.format(Locale.getDefault(), "%.0f", val);
        if (Math.abs(val) >= 10) return String.format(Locale.getDefault(), "%.1f", val);
        return String.format(Locale.getDefault(), "%.2f", val);
    }

    private String formatBaseline(double val) {
        return Math.abs(val) < 10.0
                ? new DecimalFormat("0.##").format(val)
                : new DecimalFormat("#,##0.#").format(val);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}