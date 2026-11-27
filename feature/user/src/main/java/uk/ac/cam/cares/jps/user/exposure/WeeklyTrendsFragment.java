package uk.ac.cam.cares.jps.user.exposure;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.user.R;
import uk.ac.cam.cares.jps.user.databinding.BottomSheetTrendsConfigBinding;
import uk.ac.cam.cares.jps.user.databinding.FragmentWeeklyTrendsBinding;
import uk.ac.cam.cares.jps.user.databinding.ItemWeeklyDayRowBinding;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.AggregationMode;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.DayExposure;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.DayMetricValue;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.WeekPoint;

@AndroidEntryPoint
public class WeeklyTrendsFragment extends Fragment {

    private FragmentWeeklyTrendsBinding binding;
    private WeeklyTrendsViewModel viewModel;

    private List<WeekPoint> currentPoints = Collections.emptyList();
    private int selectedWeekIdx = -1;
    private boolean barMode = false;
    @Nullable
    private LocalDate expandedDayDate = null;

    @Nullable
    private BottomSheetTrendsConfigBinding activeSheetBinding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentWeeklyTrendsBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(this).get(WeeklyTrendsViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final int initL = binding.getRoot().getPaddingLeft();
        final int initT = binding.getRoot().getPaddingTop();
        final int initR = binding.getRoot().getPaddingRight();
        final int initB = binding.getRoot().getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(initL + bars.left, initT + bars.top, initR + bars.right, initB + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());

        binding.trendsTopAppbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        binding.btnRefreshTrends.setOnClickListener(v -> viewModel.refresh());
        binding.cardMetricBufferTrigger.setOnClickListener(v -> showMetricBufferBottomSheet());

        binding.windowToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_window_6w) viewModel.setWeekWindow(6);
            else if (checkedId == R.id.btn_window_24w) viewModel.setWeekWindow(24);
            else viewModel.setWeekWindow(12);
        });

        binding.aggToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_agg_total) viewModel.setAggregation(AggregationMode.TOTAL);
            else if (checkedId == R.id.btn_agg_peak) viewModel.setAggregation(AggregationMode.PEAK);
            else viewModel.setAggregation(AggregationMode.MEAN);
        });

        binding.chartStyleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            barMode = (checkedId == R.id.btn_style_bar);
            pushChartState();
        });

        binding.btnPrevWeek.setOnClickListener(v -> {
            if (selectedWeekIdx > 0 && selectedWeekIdx < currentPoints.size()) {
                selectWeekAt(selectedWeekIdx - 1);
            }
        });
        binding.btnNextWeek.setOnClickListener(v -> {
            if (selectedWeekIdx >= 0 && selectedWeekIdx < currentPoints.size() - 1) {
                selectWeekAt(selectedWeekIdx + 1);
            }
        });

        binding.weeklyTrendChart.setOnWeekSelectedListener((index, point) -> {
            selectedWeekIdx = index;
            expandedDayDate = null;
            renderSelectedWeekDetails(index, point);
        });

        viewModel.isLoading.observe(getViewLifecycleOwner(), loading ->
                binding.progressTrends.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));
        viewModel.error.observe(getViewLifecycleOwner(), err -> {
            if (err != null && !err.isEmpty()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show();
            }
        });

        viewModel.datasets.observe(getViewLifecycleOwner(), this::renderDatasetChips);
        viewModel.calcTypes.observe(getViewLifecycleOwner(), cts -> {
            updateMetricBufferTriggerText();
            if (activeSheetBinding != null) renderSheetCalcTypes(activeSheetBinding, cts);
        });
        viewModel.buffers.observe(getViewLifecycleOwner(), bufs -> {
            updateMetricBufferTriggerText();
            if (activeSheetBinding != null) renderSheetBuffers(activeSheetBinding, bufs);
        });
        viewModel.selectedCalcType.observe(getViewLifecycleOwner(), ct -> updateMetricBufferTriggerText());
        viewModel.selectedBuffer.observe(getViewLifecycleOwner(), buf -> updateMetricBufferTriggerText());
        viewModel.weekPoints.observe(getViewLifecycleOwner(), this::renderChart);

        viewModel.initIfNeeded();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refreshIfInvalidated();
    }

    private void renderDatasetChips(List<String> datasets) {
        binding.trendsDatasetChips.removeAllViews();
        String active = viewModel.selectedDataset.getValue();
        for (String ds : datasets) {
            Chip chip = new Chip(requireContext());
            chip.setText(ds);
            chip.setCheckable(true);
            chip.setChecked(ds.equals(active));
            chip.setEnsureMinTouchTargetSize(true);
            chip.setOnClickListener(v -> viewModel.setSelectedDataset(ds));
            binding.trendsDatasetChips.addView(chip);
        }
    }

    private void updateMetricBufferTriggerText() {
        String ct = viewModel.selectedCalcType.getValue();
        String buf = viewModel.selectedBuffer.getValue();
        if (ct == null && buf == null) {
            binding.tvActiveMetricBuffer.setText("No metric available");
            return;
        }
        String bufLabel = (buf == null || "-".equals(buf)) ? "Point" : buf;
        binding.tvActiveMetricBuffer.setText((ct != null ? ct : "Metric") + " · " + bufLabel);
    }

    private void showMetricBufferBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        BottomSheetTrendsConfigBinding sheet =
                BottomSheetTrendsConfigBinding.inflate(getLayoutInflater());
        activeSheetBinding = sheet;

        String ds = viewModel.selectedDataset.getValue();
        sheet.tvSheetDatasetName.setText(ds != null ? "Dataset: " + ds : "Configure metric");

        List<String> calcTypes = viewModel.calcTypes.getValue();
        renderSheetCalcTypes(sheet, calcTypes != null ? calcTypes : Collections.emptyList());

        List<String> buffers = viewModel.buffers.getValue();
        renderSheetBuffers(sheet, buffers != null ? buffers : Collections.emptyList());

        sheet.btnSheetDone.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> activeSheetBinding = null);
        dialog.setContentView(sheet.getRoot());
        dialog.show();
    }

    private void renderSheetCalcTypes(BottomSheetTrendsConfigBinding sheet, List<String> calcTypes) {
        sheet.sheetCalctypeChips.removeAllViews();
        String active = viewModel.selectedCalcType.getValue();
        for (String ct : calcTypes) {
            Chip chip = new Chip(requireContext());
            chip.setText(ct);
            chip.setCheckable(true);
            chip.setChecked(ct.equals(active));
            chip.setOnClickListener(v -> viewModel.setSelectedCalcType(ct));
            sheet.sheetCalctypeChips.addView(chip);
        }
    }

    private void renderSheetBuffers(BottomSheetTrendsConfigBinding sheet, List<String> buffers) {
        sheet.sheetBufferGroup.removeAllViews();
        String active = viewModel.selectedBuffer.getValue();
        for (String buf : buffers) {
            MaterialButton btn = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText("-".equals(buf) ? "Point" : buf);
            btn.setTextSize(12f);
            btn.setId(View.generateViewId());
            btn.setOnClickListener(v -> viewModel.setSelectedBuffer(buf));
            sheet.sheetBufferGroup.addView(btn);
            if (buf.equals(active)) {
                sheet.sheetBufferGroup.check(btn.getId());
            }
        }
    }

    private void renderChart(List<WeekPoint> points) {
        currentPoints = points != null ? points : Collections.emptyList();
        boolean empty = currentPoints.isEmpty();

        binding.tvTrendsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.chartCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.periodSummaryStrip.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.weekBreakdownCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        selectedWeekIdx = currentPoints.size() - 1;
        expandedDayDate = null;
        pushChartState();
        renderPeriodSummaryStrip(currentPoints);
        renderSelectedWeekDetails(selectedWeekIdx, currentPoints.get(selectedWeekIdx));
    }

    private void selectWeekAt(int index) {
        selectedWeekIdx = index;
        expandedDayDate = null;
        pushChartState();
        renderSelectedWeekDetails(index, currentPoints.get(index));
    }

    private void pushChartState() {
        if (currentPoints.isEmpty()) return;
        boolean showBand = viewModel.aggregation.getValue() == AggregationMode.MEAN;
        binding.weeklyTrendChart.setData(currentPoints, selectedWeekIdx, showBand, barMode);

        AggregationMode mode = viewModel.aggregation.getValue();
        String aggText = mode == AggregationMode.TOTAL ? "Sum"
                : (mode == AggregationMode.PEAK ? "Peak" : "Mean");
        binding.tvLegendAgg.setText(aggText);
        binding.legendBandGroup.setVisibility((showBand && !barMode) ? View.VISIBLE : View.GONE);
    }

    private void renderPeriodSummaryStrip(List<WeekPoint> points) {
        double sum = 0.0;
        WeekPoint minPt = points.get(0);
        WeekPoint maxPt = points.get(0);
        for (WeekPoint p : points) {
            sum += p.displayValue;
            if (p.displayValue < minPt.displayValue) minPt = p;
            if (p.displayValue > maxPt.displayValue) maxPt = p;
        }
        double avg = sum / points.size();
        String unitSuffix = points.get(0).unit.isEmpty() ? "" : " " + points.get(0).unit;

        binding.tvStatAvgLabel.setText(String.format(Locale.getDefault(), "%dW Avg", points.size()));
        binding.tvStatAvgVal.setText(formatNumber(avg) + unitSuffix);
        binding.tvStatLowLabel.setText("Low · " + minPt.weekLabel);
        binding.tvStatLowVal.setText(formatNumber(minPt.displayValue) + unitSuffix);
        binding.tvStatHighLabel.setText("High · " + maxPt.weekLabel);
        binding.tvStatHighVal.setText(formatNumber(maxPt.displayValue) + unitSuffix);
    }

    private void renderSelectedWeekDetails(int index, WeekPoint point) {
        DateTimeFormatter fullFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());
        LocalDate sunday = point.weekStart.plusDays(6);
        String fullRange = point.weekStart.format(fullFmt) + " – "
                + sunday.format(fullFmt) + ", " + sunday.getYear();

        binding.tvSelectedWeekHeader.setText(fullRange);
        binding.tvSelectedWeekMeta.setText(String.format(Locale.getDefault(),
                "%s · %d/7 days calculated · %d trips",
                point.weekKey, point.calculatedDays, point.totalTrips));

        binding.tvSelectedWeekValue.setText(formatNumber(point.displayValue));
        binding.tvSelectedWeekUnit.setText(point.unit);
        binding.tvSelectedWeekUnit.setVisibility(point.unit.isEmpty() ? View.GONE : View.VISIBLE);

        String unitSuffix = point.unit.isEmpty() ? "" : " " + point.unit;
        binding.tvSelectedWeekRange.setText(String.format(Locale.getDefault(),
                "%s–%s%s range", formatNumber(point.minValue), formatNumber(point.maxValue), unitSuffix));

        renderDeltaText(index, point);

        // Stepper button enabled state
        boolean hasPrev = index > 0;
        boolean hasNext = index < currentPoints.size() - 1;
        binding.btnPrevWeek.setEnabled(hasPrev);
        binding.btnPrevWeek.setAlpha(hasPrev ? 1f : 0.3f);
        binding.btnNextWeek.setEnabled(hasNext);
        binding.btnNextWeek.setAlpha(hasNext ? 1f : 0.3f);

        renderDailyBreakdown(point);
    }

    private void renderDeltaText(int index, WeekPoint point) {
        int mutedColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF525E63);
        int goodColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorSecondary, 0xFF006D35);
        int warnColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorError, 0xFFB45309);

        if (index <= 0 || index - 1 >= currentPoints.size()) {
            binding.tvSelectedWeekDelta.setText("First week in window");
            binding.tvSelectedWeekDelta.setTextColor(mutedColor);
            return;
        }

        double prevVal = currentPoints.get(index - 1).displayValue;
        if (Math.abs(prevVal) < 1e-6) {
            binding.tvSelectedWeekDelta.setText("– vs prior wk");
            binding.tvSelectedWeekDelta.setTextColor(mutedColor);
            return;
        }

        double pct = ((point.displayValue - prevVal) / prevVal) * 100.0;
        if (Math.abs(pct) <= 0.5) {
            binding.tvSelectedWeekDelta.setText("Unchanged vs prior wk");
            binding.tvSelectedWeekDelta.setTextColor(mutedColor);
            return;
        }

        String arrow = pct > 0 ? "↑ +" : "↓ ";
        binding.tvSelectedWeekDelta.setText(
                String.format(Locale.getDefault(), "%s%.1f%% vs prior wk", arrow, pct));

        String ds = viewModel.selectedDataset.getValue();
        boolean lowerIsBetter = isLowerBetterDataset(ds);
        boolean favorable = lowerIsBetter ? pct < 0 : pct > 0;
        binding.tvSelectedWeekDelta.setTextColor(favorable ? goodColor : warnColor);
    }

    private void renderDailyBreakdown(WeekPoint point) {
        binding.tvBreakdownSubtitle.setText(String.format(Locale.getDefault(),
                "%d of 7 days calculated · %d trips", point.calculatedDays, point.totalTrips));
        binding.tvBreakdownWeekLabel.setText(point.weekLabel);
        binding.weekDaysContainer.removeAllViews();

        Map<LocalDate, DayExposure> byDate = new HashMap<>();
        for (DayExposure d : point.days) {
            byDate.put(d.date, d);
        }

        String ds = viewModel.selectedDataset.getValue();
        String ct = viewModel.selectedCalcType.getValue();
        String buf = viewModel.selectedBuffer.getValue();
        String unitSuffix = point.unit.isEmpty() ? "" : " " + point.unit;

        // Mean of calculated days in this week for the comparison tick
        double weekMean = 0.0;
        int count = 0;
        for (DayExposure d : point.days) {
            DayMetricValue m = (ds != null && ct != null && buf != null) ? d.find(ds, ct, buf) : null;
            if (m != null) {
                weekMean += m.value;
                count++;
            }
        }
        if (count > 0) weekMean /= count;
        final double scaleMax = Math.max(point.maxValue * 1.12, 1e-6);
        final double meanRatio = Math.min(1.0, Math.max(0.0, weekMean / scaleMax));

        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault());
        int primaryColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorPrimary, 0xFF00687C);
        int mutedColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF525E63);
        int goodColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorSecondary, 0xFF006D35);
        int warnColor = MaterialColors.getColor(
                requireView(), com.google.android.material.R.attr.colorError, 0xFFB45309);
        boolean lowerIsBetter = isLowerBetterDataset(ds);

        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = point.weekStart.plusDays(offset);
            DayExposure day = byDate.get(date);
            DayMetricValue val = (day != null && ds != null && ct != null && buf != null)
                    ? day.find(ds, ct, buf) : null;

            ItemWeeklyDayRowBinding row = ItemWeeklyDayRowBinding.inflate(
                    getLayoutInflater(), binding.weekDaysContainer, false);

            row.tvDayDate.setText(date.format(dayFmt));
            row.dayRowDivider.setVisibility(offset == 6 ? View.GONE : View.VISIBLE);

            if (day == null || val == null) {
                row.tvDayTrips.setText("No calculated trips");
                row.tvDayValue.setText("–");
                row.tvDayValue.setTextColor(mutedColor);
                row.ivDayExpand.setVisibility(View.GONE);
                row.dayBarRow.setVisibility(View.GONE);
                row.dayRowHeader.setAlpha(0.62f);
                binding.weekDaysContainer.addView(row.getRoot());
                continue;
            }

            row.tvDayTrips.setText(String.format(Locale.getDefault(),
                    "%d %s", day.tripCount, day.tripCount == 1 ? "trip" : "trips"));
            row.tvDayValue.setText(formatNumber(val.value) + unitSuffix);
            row.dayBarFill.setBackgroundColor(primaryColor);

            final double fillRatio = Math.min(1.0, Math.max(0.04, val.value / scaleMax));
            row.dayBarTrack.post(() -> {
                int trackW = row.dayBarTrack.getWidth();
                if (trackW <= 0) return;
                ViewGroup.LayoutParams fillLp = row.dayBarFill.getLayoutParams();
                fillLp.width = Math.max(4, (int) Math.round(trackW * fillRatio));
                row.dayBarFill.setLayoutParams(fillLp);

                row.dayBarMeanTick.setTranslationX((float) (trackW * meanRatio));
            });

            // % difference vs weekly mean
            if (Math.abs(weekMean) > 1e-6) {
                double diffPct = ((val.value - weekMean) / weekMean) * 100.0;
                if (Math.abs(diffPct) < 2.0) {
                    row.tvDayDelta.setText("Near avg");
                    row.tvDayDelta.setTextColor(mutedColor);
                } else {
                    String sign = diffPct > 0 ? "+" : "";
                    row.tvDayDelta.setText(String.format(Locale.getDefault(), "%s%.0f%% vs avg", sign, diffPct));
                    boolean favorable = lowerIsBetter ? diffPct < 0 : diffPct > 0;
                    row.tvDayDelta.setTextColor(favorable ? goodColor : warnColor);
                }
            } else {
                row.tvDayDelta.setText("");
            }

            // Expandable buffer sensitivity breakdown
            boolean isExpanded = date.equals(expandedDayDate);
            row.dayBuffersExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            row.ivDayExpand.setRotation(isExpanded ? 180f : 0f);
            if (isExpanded) {
                populateBufferSensitivity(row.dayBuffersExpanded, day, ds, ct, buf);
            }

            row.dayRowHeader.setOnClickListener(v -> {
                expandedDayDate = date.equals(expandedDayDate) ? null : date;
                renderDailyBreakdown(point);
            });

            binding.weekDaysContainer.addView(row.getRoot());
        }
    }

    private void populateBufferSensitivity(LinearLayout container, DayExposure day,
                                           String ds, String ct, String activeBuf) {
        container.removeAllViews();
        for (DayMetricValue m : day.metrics) {
            if (!m.dataset.equals(ds) || !m.calcType.equals(ct)) continue;
            LinearLayout line = new LinearLayout(requireContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(8, 6, 8, 6);

            TextView left = new TextView(requireContext());
            left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            left.setText("-".equals(m.buffer) ? "Point buffer" : m.buffer + " buffer");
            left.setTextSize(12f);
            if (m.buffer.equals(activeBuf)) {
                left.setTextColor(MaterialColors.getColor(
                        requireView(), com.google.android.material.R.attr.colorPrimary, 0xFF00687C));
            }

            TextView right = new TextView(requireContext());
            String unit = m.unit.isEmpty() ? "" : " " + m.unit;
            right.setText(formatNumber(m.value) + unit);
            right.setTextSize(12f);
            right.setFontFeatureSettings("tnum");

            line.addView(left);
            line.addView(right);
            container.addView(line);
        }
    }

    private static boolean isLowerBetterDataset(@Nullable String ds) {
        if (ds == null) return true;
        String lower = ds.toLowerCase(Locale.ROOT);
        return !(lower.contains("ndvi") || lower.contains("gvi")
                || lower.contains("green") || lower.contains("park"));
    }

    private static String formatNumber(double v) {
        return Math.abs(v) < 10.0
                ? new DecimalFormat("0.##").format(v)
                : new DecimalFormat("#,##0.##").format(v);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        activeSheetBinding = null;
        binding = null;
    }
}