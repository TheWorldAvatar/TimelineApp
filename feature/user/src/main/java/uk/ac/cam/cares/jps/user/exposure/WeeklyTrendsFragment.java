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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.user.R;
import uk.ac.cam.cares.jps.user.databinding.FragmentWeeklyTrendsBinding;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.AggregationMode;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.DayExposure;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.DayMetricValue;
import uk.ac.cam.cares.jps.user.exposure.WeeklyTrendsViewModel.WeekPoint;

@AndroidEntryPoint
public class WeeklyTrendsFragment extends Fragment {

    private FragmentWeeklyTrendsBinding binding;
    private WeeklyTrendsViewModel viewModel;
    private int selectedWeekIdx = -1;

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

        binding.weeklyTrendChart.setOnWeekSelectedListener((index, point) -> {
            selectedWeekIdx = index;
            renderSelectedWeekDetails(point);
        });

        viewModel.isLoading.observe(getViewLifecycleOwner(), loading ->
                binding.progressTrends.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));

        viewModel.error.observe(getViewLifecycleOwner(), err -> {
            if (err != null && !err.isEmpty()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show();
            }
        });

        viewModel.datasets.observe(getViewLifecycleOwner(), this::renderDatasetChips);
        viewModel.calcTypes.observe(getViewLifecycleOwner(), this::renderCalcTypeChips);
        viewModel.buffers.observe(getViewLifecycleOwner(), this::renderBufferButtons);
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
            chip.setOnClickListener(v -> viewModel.setSelectedDataset(ds));
            binding.trendsDatasetChips.addView(chip);
        }
    }

    private void renderCalcTypeChips(List<String> calcTypes) {
        binding.trendsCalctypeChips.removeAllViews();
        String active = viewModel.selectedCalcType.getValue();
        for (String ct : calcTypes) {
            Chip chip = new Chip(requireContext());
            chip.setText(ct);
            chip.setCheckable(true);
            chip.setChecked(ct.equals(active));
            chip.setOnClickListener(v -> viewModel.setSelectedCalcType(ct));
            binding.trendsCalctypeChips.addView(chip);
        }
    }

    private void renderBufferButtons(List<String> buffers) {
        binding.trendsBufferGroup.removeAllViews();
        String active = viewModel.selectedBuffer.getValue();
        for (String buf : buffers) {
            MaterialButton btn = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(buf);
            btn.setTextSize(12f);
            btn.setId(View.generateViewId());
            btn.setOnClickListener(v -> viewModel.setSelectedBuffer(buf));
            binding.trendsBufferGroup.addView(btn);
            if (buf.equals(active)) {
                binding.trendsBufferGroup.check(btn.getId());
            }
        }
    }

    private void renderChart(List<WeekPoint> points) {
        boolean empty = points == null || points.isEmpty();
        binding.tvTrendsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.chartCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.weekBreakdownCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        selectedWeekIdx = points.size() - 1;
        boolean showBand = viewModel.aggregation.getValue() == AggregationMode.MEAN;
        binding.weeklyTrendChart.setData(points, selectedWeekIdx, showBand);
        renderSelectedWeekDetails(points.get(selectedWeekIdx));
    }

    private void renderSelectedWeekDetails(WeekPoint point) {
        binding.tvSelectedWeekHeader.setText(point.weekKey + " · " + point.dateRange);
        String unitSuffix = point.unit.isEmpty() ? "" : " " + point.unit;
        binding.tvSelectedWeekValue.setText(formatNumber(point.displayValue) + unitSuffix);
        binding.tvSelectedWeekMeta.setText(String.format(Locale.getDefault(),
                "Min %s – Max %s%s · %d calculated days · %d trips",
                formatNumber(point.minValue), formatNumber(point.maxValue), unitSuffix,
                point.calculatedDays, point.totalTrips));

        binding.weekDaysContainer.removeAllViews();
        String ds = viewModel.selectedDataset.getValue();
        String ct = viewModel.selectedCalcType.getValue();
        String buf = viewModel.selectedBuffer.getValue();
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault());

        for (DayExposure day : point.days) {
            DayMetricValue val = (ds != null && ct != null && buf != null) ? day.find(ds, ct, buf) : null;
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 10, 0, 10);

            TextView left = new TextView(requireContext());
            left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            left.setText(day.date.format(dayFmt) + " (" + day.tripCount + " trips)");
            left.setTextSize(13f);

            TextView right = new TextView(requireContext());
            right.setText(val != null ? formatNumber(val.value) + unitSuffix : "–");
            right.setTextSize(13f);

            row.addView(left);
            row.addView(right);
            binding.weekDaysContainer.addView(row);
        }
    }

    private static String formatNumber(double v) {
        return Math.abs(v) < 10.0 ? new DecimalFormat("0.##").format(v) : new DecimalFormat("#,##0.##").format(v);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}