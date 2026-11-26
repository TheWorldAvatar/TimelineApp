package uk.ac.cam.cares.jps.user.exposure;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.TimelineDateNavigationViewModel;
import uk.ac.cam.cares.jps.user.R;
import uk.ac.cam.cares.jps.user.databinding.FragmentExposureOverviewBinding;
import uk.ac.cam.cares.jps.user.setting.ExposureSettingFragment;

@AndroidEntryPoint
public class ExposureOverviewFragment extends Fragment implements ExposureDayAdapter.Listener {

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault());

    private FragmentExposureOverviewBinding binding;
    private ExposureOverviewViewModel viewModel;
    private TimelineDateNavigationViewModel dateNavViewModel;
    private ExposureDayAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentExposureOverviewBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(this).get(ExposureOverviewViewModel.class);
        dateNavViewModel = new ViewModelProvider(requireActivity()).get(TimelineDateNavigationViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final View root = binding.getRoot();
        final int initLeft = root.getPaddingLeft();
        final int initTop = root.getPaddingTop();
        final int initRight = root.getPaddingRight();
        final int initBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    initLeft + bars.left,
                    initTop + bars.top,
                    initRight + bars.right,
                    initBottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);

        binding.exposureOverviewToolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp());

        binding.exposureOverviewToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh_exposures) {
                viewModel.refreshCurrentMonth();
                return true;
            }
            return false;
        });

        adapter = new ExposureDayAdapter(this);
        binding.rvExposureDays.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvExposureDays.setAdapter(adapter);

        binding.btnPrevMonth.setOnClickListener(v -> viewModel.previousMonth());
        binding.btnNextMonth.setOnClickListener(v -> viewModel.nextMonth());

        binding.filterToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_filter_calculated) {
                viewModel.setFilter(ExposureOverviewViewModel.Filter.CALCULATED);
            } else if (checkedId == R.id.btn_filter_missing) {
                viewModel.setFilter(ExposureOverviewViewModel.Filter.MISSING);
            } else {
                viewModel.setFilter(ExposureOverviewViewModel.Filter.ALL);
            }
        });

        getParentFragmentManager().setFragmentResultListener(
                ExposureSettingFragment.RESULT_KEY_AUTO_CALCULATE,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    String dateStr = result.getString(ExposureSettingFragment.RESULT_CALC_DATE);
                    if (dateStr == null || dateStr.isEmpty()) return;
                    try {
                        LocalDate date = LocalDate.parse(dateStr);
                        Toast.makeText(requireContext(),
                                "Starting exposure calculation for " + date,
                                Toast.LENGTH_SHORT).show();
                        // Short delay ensures asynchronous preference write finishes before reading params
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (viewModel != null) {
                                viewModel.triggerCalculationForDay(date);
                            }
                        }, 150);
                    } catch (Exception ignored) {
                    }
                });

        viewModel.selectedMonth.observe(getViewLifecycleOwner(), month -> {
            if (month == null) return;
            binding.tvMonthTitle.setText(month.format(MONTH_FORMAT));
            boolean isCurrentOrLater = !month.isBefore(YearMonth.now());
            binding.btnNextMonth.setEnabled(!isCurrentOrLater);
            binding.btnNextMonth.setAlpha(isCurrentOrLater ? 0.35f : 1f);
        });

        viewModel.isLoading.observe(getViewLifecycleOwner(), loading ->
                binding.progressLoading.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));

        viewModel.allDays.observe(getViewLifecycleOwner(), this::updateSummaryHeader);

        viewModel.visibleDays.observe(getViewLifecycleOwner(), days -> {
            adapter.submitList(days);
            boolean empty = (days == null || days.isEmpty())
                    && !Boolean.TRUE.equals(viewModel.isLoading.getValue());
            binding.tvEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.tvEmptyState.setText("No trajectory days match this filter for this month.");
        });

        viewModel.error.observe(getViewLifecycleOwner(), err -> {
            if (err != null && !err.isEmpty()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show();
            }
        });

        viewModel.loadMonthIfNeeded();
    }

    private void updateSummaryHeader(List<ExposureOverviewViewModel.DaySummary> all) {
        if (all == null) return;
        int calculated = 0;
        int missing = 0;
        for (ExposureOverviewViewModel.DaySummary d : all) {
            if (d.status == ExposureOverviewViewModel.DayStatus.CALCULATED) {
                calculated++;
            } else {
                missing++;
            }
        }
        binding.tvMonthSummary.setText(calculated + " of " + all.size() + " trajectory days calculated");
        binding.btnFilterAll.setText("All (" + all.size() + ")");
        binding.btnFilterCalculated.setText("Calculated (" + calculated + ")");
        binding.btnFilterMissing.setText("Missing (" + missing + ")");
    }

    @Override
    public void onViewOnMap(LocalDate date) {
        dateNavViewModel.requestDate(date);
        NavHostFragment.findNavController(this).navigateUp();
    }

    @Override
    public void onCalculate(LocalDate date) {
        Bundle args = new Bundle();
        args.putString(ExposureSettingFragment.ARG_PENDING_CALC_DATE, date.toString());
        NavHostFragment.findNavController(this).navigate(R.id.action_global_exposure_setting, args);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshIfInvalidated();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}