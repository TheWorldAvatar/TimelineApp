package uk.ac.cam.cares.jps.user.exposure;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.user.R;
import uk.ac.cam.cares.jps.user.databinding.BottomSheetTripPickerBinding;
import uk.ac.cam.cares.jps.user.databinding.FragmentCompareTripsBinding;
import uk.ac.cam.cares.jps.user.exposure.CompareTripsViewModel.ComparableTrip;
import uk.ac.cam.cares.jps.user.exposure.CompareTripsViewModel.MetricEntry;

@AndroidEntryPoint
public class CompareTripsFragment extends Fragment {

    private FragmentCompareTripsBinding binding;
    private CompareTripsViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCompareTripsBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(this).get(CompareTripsViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final int initLeft = binding.getRoot().getPaddingLeft();
        final int initTop = binding.getRoot().getPaddingTop();
        final int initRight = binding.getRoot().getPaddingRight();
        final int initBottom = binding.getRoot().getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    initLeft + bars.left,
                    initTop + bars.top,
                    initRight + bars.right,
                    initBottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());

        binding.compareTopAppbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        View.OnClickListener openPicker = v -> showTripPickerBottomSheet();
        binding.btnOpenTripPicker.setOnClickListener(openPicker);
        binding.btnAddTripInline.setOnClickListener(openPicker);
        binding.btnEmptySelectTrips.setOnClickListener(openPicker);

        viewModel.selectedTrips.observe(getViewLifecycleOwner(), trips -> renderAll());
        viewModel.datasets.observe(getViewLifecycleOwner(), datasets -> renderDatasetChips());
        viewModel.selectedDataset.observe(getViewLifecycleOwner(), ds -> renderAll());
        viewModel.buffers.observe(getViewLifecycleOwner(), buffers -> renderBufferToggle());
        viewModel.selectedBuffer.observe(getViewLifecycleOwner(), buf -> renderComparisonCards());

        viewModel.initIfNeeded();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshIfInvalidated();
        }
    }

    private void renderAll() {
        if (binding == null) return;
        List<ComparableTrip> selected = viewModel.selectedTrips.getValue();
        if (selected == null) selected = new ArrayList<>();

        binding.btnOpenTripPicker.setText(
                String.format(Locale.getDefault(), "Days (%d/%d)",
                        selected.size(), CompareTripsViewModel.MAX_SELECTED_TRIPS));
        binding.btnAddTripInline.setVisibility(
                selected.size() < CompareTripsViewModel.MAX_SELECTED_TRIPS ? View.VISIBLE : View.GONE);

        renderSelectedTripStrip(selected);

        boolean canCompare = selected.size() >= 2;
        binding.emptyCompareCard.setVisibility(canCompare ? View.GONE : View.VISIBLE);
        binding.comparisonContentGroup.setVisibility(canCompare ? View.VISIBLE : View.GONE);

        if (canCompare) {
            renderDatasetChips();
            renderBufferToggle();
            renderComparisonCards();
            renderMetricsMatrix(selected);
        }
    }

    private void renderSelectedTripStrip(List<ComparableTrip> selected) {
        binding.selectedTripsContainer.removeAllViews();
        int surface = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorSurface, Color.WHITE);
        int outline = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOutline, 0xFF899295);
        int onSurface = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOnSurface, 0xFF0F172A);
        int onSurfaceVariant = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF64748B);
        int primary = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorPrimary, 0xFF00687C);

        for (int i = 0; i < selected.size(); i++) {
            ComparableTrip trip = selected.get(i);
            int seriesColor = TripPickerAdapter.SERIES_COLORS[i % TripPickerAdapter.SERIES_COLORS.length];

            MaterialCardView card = new MaterialCardView(requireContext());
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(8);
            card.setLayoutParams(cardLp);
            card.setCardBackgroundColor(surface);
            card.setRadius(dp(14));
            card.setCardElevation(0f);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(outline);

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));

            TextView badge = new TextView(requireContext());
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(26), dp(26));
            badge.setLayoutParams(badgeLp);
            badge.setGravity(Gravity.CENTER);
            badge.setText(TripPickerAdapter.SLOT_LETTERS[i]);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setTypeface(null, Typeface.BOLD);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(8));
            badgeBg.setColor(seriesColor);
            badge.setBackground(badgeBg);
            row.addView(badge);

            LinearLayout info = new LinearLayout(requireContext());
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoLp.setMarginStart(dp(10));
            info.setLayoutParams(infoLp);
            info.setOrientation(LinearLayout.VERTICAL);

            TextView titleTv = new TextView(requireContext());
            titleTv.setText(trip.title);
            titleTv.setTextColor(onSurface);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            titleTv.setTypeface(null, Typeface.BOLD);
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(titleTv);

            TextView subTv = new TextView(requireContext());
            subTv.setText(trip.formattedSummaryLine());
            subTv.setTextColor(onSurfaceVariant);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subTv.setFontFeatureSettings("tnum");
            subTv.setMaxLines(1);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(subTv);
            row.addView(info);

            if (i == 0) {
                TextView baseTv = new TextView(requireContext());
                baseTv.setText("Baseline");
                baseTv.setTextColor(onSurfaceVariant);
                baseTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                baseTv.setTypeface(null, Typeface.BOLD);
                baseTv.setPadding(dp(6), 0, dp(4), 0);
                row.addView(baseTv);
            } else {
                MaterialButton makeBaseBtn = new MaterialButton(
                        requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                makeBaseBtn.setText("Make A");
                makeBaseBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                makeBaseBtn.setTextColor(primary);
                makeBaseBtn.setMinHeight(dp(32));
                makeBaseBtn.setMinimumHeight(dp(32));
                makeBaseBtn.setInsetTop(0);
                makeBaseBtn.setInsetBottom(0);
                makeBaseBtn.setPadding(dp(10), 0, dp(10), 0);
                makeBaseBtn.setOnClickListener(v -> viewModel.makeBaseline(trip.id));
                row.addView(makeBaseBtn);
            }

            ImageButton removeBtn = new ImageButton(requireContext());
            LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            removeBtn.setLayoutParams(rmLp);
            removeBtn.setImageResource(uk.ac.cam.cares.jps.ui.base.R.drawable.ic_close_24);
            removeBtn.setBackgroundColor(Color.TRANSPARENT);
            removeBtn.setContentDescription("Remove trip");
            removeBtn.setColorFilter(onSurfaceVariant);
            removeBtn.setOnClickListener(v -> viewModel.removeSelectedTrip(trip.id));
            row.addView(removeBtn);

            card.addView(row);
            binding.selectedTripsContainer.addView(card);
        }
    }

    private void renderDatasetChips() {
        List<String> datasets = viewModel.datasets.getValue();
        String activeDataset = viewModel.selectedDataset.getValue();
        binding.datasetChipGroup.setOnCheckedStateChangeListener(null);
        binding.datasetChipGroup.removeAllViews();

        if (datasets == null || datasets.isEmpty()) return;

        for (String ds : datasets) {
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(ds);
            chip.setCheckable(true);
            chip.setChecked(ds.equals(activeDataset));
            chip.setTag(ds);
            binding.datasetChipGroup.addView(chip);
        }

        binding.datasetChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            View checked = group.findViewById(checkedIds.get(0));
            if (checked != null && checked.getTag() instanceof String) {
                viewModel.setDataset((String) checked.getTag());
            }
        });
    }

    private void renderBufferToggle() {
        List<String> buffers = viewModel.buffers.getValue();
        String activeBuffer = viewModel.selectedBuffer.getValue();
        binding.bufferToggleGroup.clearOnButtonCheckedListeners();
        binding.bufferToggleGroup.removeAllViews();

        if (buffers == null || buffers.isEmpty()) return;

        for (String buf : buffers) {
            MaterialButton btn = new MaterialButton(
                    requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setId(View.generateViewId());
            btn.setText("-".equals(buf) ? "Direct" : buf);
            btn.setTag(buf);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setMinHeight(dp(38));
            btn.setMinimumHeight(dp(38));
            binding.bufferToggleGroup.addView(btn);
            if (buf.equals(activeBuffer)) {
                binding.bufferToggleGroup.check(btn.getId());
            }
        }

        binding.bufferToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            View btn = group.findViewById(checkedId);
            if (btn != null && btn.getTag() instanceof String) {
                viewModel.setBuffer((String) btn.getTag());
            }
        });
    }

    private void renderComparisonCards() {
        if (binding == null) return;
        binding.comparisonCardsContainer.removeAllViews();

        List<ComparableTrip> selected = viewModel.selectedTrips.getValue();
        String activeDataset = viewModel.selectedDataset.getValue();
        String activeBuffer = viewModel.selectedBuffer.getValue();
        if (selected == null || selected.size() < 2 || activeDataset == null || activeBuffer == null) {
            return;
        }

        // Collect all unique calculation types for the active dataset + buffer
        List<String> calcTypes = new ArrayList<>();
        for (ComparableTrip trip : selected) {
            for (MetricEntry m : trip.metrics) {
                if (m.dataset.equals(activeDataset) && m.buffer.equals(activeBuffer)
                        && !calcTypes.contains(m.calcType)) {
                    calcTypes.add(m.calcType);
                }
            }
        }

        int surface = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorSurface, Color.WHITE);
        int outline = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOutline, 0xFF899295);
        int onSurface = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOnSurface, 0xFF0F172A);
        int onSurfaceVariant = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF64748B);
        int surfaceVariant = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE2E8F0);

        for (String calcType : calcTypes) {
            double maxVal = 0.0;
            Double baselineVal = null;
            boolean isAvgMetric = false;
            for (int i = 0; i < selected.size(); i++) {
                MetricEntry m = selected.get(i).findMetric(activeDataset, calcType, activeBuffer);
                if (m != null) {
                    isAvgMetric = m.isAverage;
                    if (m.numericValue != null) {
                        if (m.numericValue > maxVal) maxVal = m.numericValue;
                        if (i == 0) baselineVal = m.numericValue;
                    }
                }
            }

            MaterialCardView card = new MaterialCardView(requireContext());
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(12);
            card.setLayoutParams(cardLp);
            card.setCardBackgroundColor(surface);
            card.setRadius(dp(16));
            card.setCardElevation(0f);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(outline);

            LinearLayout body = new LinearLayout(requireContext());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(16), dp(14), dp(16), dp(14));

            // Header
            LinearLayout header = new LinearLayout(requireContext());
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView titleTv = new TextView(requireContext());
            titleTv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            titleTv.setText(calcType);
            titleTv.setTextColor(onSurface);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            titleTv.setTypeface(null, Typeface.BOLD);
            header.addView(titleTv);

            TextView bufTv = new TextView(requireContext());
            String aggPrefix = isAvgMetric ? "Daily avg · " : "Daily total · ";
            bufTv.setText("-".equals(activeBuffer) ? aggPrefix + activeDataset : aggPrefix + activeBuffer);
            bufTv.setTextColor(onSurfaceVariant);
            bufTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            header.addView(bufTv);
            body.addView(header);

            // Bars per selected trip
            for (int i = 0; i < selected.size(); i++) {
                ComparableTrip trip = selected.get(i);
                int seriesColor = TripPickerAdapter.SERIES_COLORS[i % TripPickerAdapter.SERIES_COLORS.length];
                MetricEntry m = trip.findMetric(activeDataset, calcType, activeBuffer);

                LinearLayout rowWrap = new LinearLayout(requireContext());
                LinearLayout.LayoutParams rwLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rwLp.topMargin = dp(10);
                rowWrap.setLayoutParams(rwLp);
                rowWrap.setOrientation(LinearLayout.VERTICAL);

                LinearLayout topRow = new LinearLayout(requireContext());
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                View dot = new View(requireContext());
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
                dot.setLayoutParams(dotLp);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(seriesColor);
                dot.setBackground(dotBg);
                topRow.addView(dot);

                TextView labelTv = new TextView(requireContext());
                LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lblLp.setMarginStart(dp(6));
                labelTv.setLayoutParams(lblLp);
                labelTv.setText(TripPickerAdapter.SLOT_LETTERS[i] + " · " + trip.dateLabel);
                labelTv.setTextColor(onSurface);
                labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                labelTv.setMaxLines(1);
                labelTv.setEllipsize(TextUtils.TruncateAt.END);
                topRow.addView(labelTv);

                TextView valueTv = new TextView(requireContext());
                valueTv.setFontFeatureSettings("tnum");
                valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                if (m != null) {
                    String valText = m.displayValue + (m.unit.isEmpty() ? "" : " " + m.unit);
                    String deltaSuffix = "";
                    int deltaColor = onSurfaceVariant;
                    if (i == 0) {
                        deltaSuffix = "  (Baseline)";
                    } else if (baselineVal != null && baselineVal > 0 && m.numericValue != null) {
                        long pct = Math.round(((m.numericValue - baselineVal) / baselineVal) * 100.0);
                        deltaSuffix = pct > 0 ? "  (+" + pct + "%)" : "  (" + pct + "%)";
                        if (pct > 0) deltaColor = 0xFFB45309;
                        else if (pct < 0) deltaColor = 0xFF15803D;
                    }
                    valueTv.setText(valText + deltaSuffix);
                    valueTv.setTextColor(deltaColor == onSurfaceVariant ? onSurface : deltaColor);
                    valueTv.setTypeface(null, Typeface.BOLD);
                } else {
                    valueTv.setText("Not calculated");
                    valueTv.setTextColor(onSurfaceVariant);
                }
                topRow.addView(valueTv);
                rowWrap.addView(topRow);

                // Bar track
                if (m != null && m.numericValue != null) {
                    LinearLayout track = new LinearLayout(requireContext());
                    LinearLayout.LayoutParams trkLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
                    trkLp.topMargin = dp(4);
                    track.setLayoutParams(trkLp);
                    GradientDrawable trackBg = new GradientDrawable();
                    trackBg.setCornerRadius(dp(4));
                    trackBg.setColor(surfaceVariant);
                    track.setBackground(trackBg);

                    float ratio = maxVal > 0 ? (float) Math.max(0.06, Math.min(1.0, m.numericValue / maxVal)) : 0.06f;
                    View fill = new View(requireContext());
                    fill.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, ratio));
                    GradientDrawable fillBg = new GradientDrawable();
                    fillBg.setCornerRadius(dp(4));
                    fillBg.setColor(seriesColor);
                    fill.setBackground(fillBg);

                    View spacer = new View(requireContext());
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - ratio));

                    track.addView(fill);
                    track.addView(spacer);
                    rowWrap.addView(track);
                } else {
                    TextView missingTrack = new TextView(requireContext());
                    LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    mLp.topMargin = dp(4);
                    missingTrack.setLayoutParams(mLp);
                    missingTrack.setPadding(dp(8), dp(4), dp(8), dp(4));
                    missingTrack.setText("Run exposure calculation for this dataset/buffer");
                    missingTrack.setTextColor(onSurfaceVariant);
                    missingTrack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                    GradientDrawable missingBg = new GradientDrawable();
                    missingBg.setCornerRadius(dp(6));
                    missingBg.setStroke(dp(1), outline, dp(4), dp(3));
                    missingTrack.setBackground(missingBg);
                    rowWrap.addView(missingTrack);
                }

                body.addView(rowWrap);
            }

            card.addView(body);
            binding.comparisonCardsContainer.addView(card);
        }
    }

    private void renderMetricsMatrix(List<ComparableTrip> selected) {
        binding.matrixTableContainer.removeAllViews();

        int onSurface = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOnSurface, 0xFF0F172A);
        int onSurfaceVariant = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF64748B);
        int outline = MaterialColors.getColor(
                binding.getRoot(), com.google.android.material.R.attr.colorOutline, 0xFFE2E8F0);

        // Header row
        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(0, 0, 0, dp(8));

        TextView metricCol = new TextView(requireContext());
        metricCol.setLayoutParams(new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        metricCol.setText("Dataset · Metric · Buffer");
        metricCol.setTextColor(onSurfaceVariant);
        metricCol.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        metricCol.setTypeface(null, Typeface.BOLD);
        headerRow.addView(metricCol);

        for (int i = 0; i < selected.size(); i++) {
            TextView tripCol = new TextView(requireContext());
            tripCol.setLayoutParams(new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            tripCol.setGravity(Gravity.END);
            tripCol.setText(TripPickerAdapter.SLOT_LETTERS[i]);
            tripCol.setTextColor(TripPickerAdapter.SERIES_COLORS[i % TripPickerAdapter.SERIES_COLORS.length]);
            tripCol.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tripCol.setTypeface(null, Typeface.BOLD);
            headerRow.addView(tripCol);
        }
        binding.matrixTableContainer.addView(headerRow);

        View divider = new View(requireContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(outline);
        divider.setAlpha(0.25f);
        binding.matrixTableContainer.addView(divider);

        // Unique combinations preserving discovery order
        Map<String, String[]> combos = new LinkedHashMap<>();
        for (ComparableTrip trip : selected) {
            for (MetricEntry m : trip.metrics) {
                String k = m.dataset + "|" + m.calcType + "|" + m.buffer;
                combos.putIfAbsent(k, new String[]{m.dataset, m.calcType, m.buffer});
            }
        }

        for (String[] combo : combos.values()) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            LinearLayout labelCol = new LinearLayout(requireContext());
            labelCol.setLayoutParams(new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
            labelCol.setOrientation(LinearLayout.VERTICAL);
            labelCol.setPadding(0, 0, dp(8), 0);

            TextView dsTv = new TextView(requireContext());
            dsTv.setText(combo[0]);
            dsTv.setTextColor(onSurface);
            dsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            dsTv.setTypeface(null, Typeface.BOLD);
            dsTv.setMaxLines(1);
            dsTv.setEllipsize(TextUtils.TruncateAt.END);
            labelCol.addView(dsTv);

            TextView subTv = new TextView(requireContext());
            subTv.setText(combo[1] + ("-".equals(combo[2]) ? "" : " · " + combo[2]));
            subTv.setTextColor(onSurfaceVariant);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            subTv.setMaxLines(1);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            labelCol.addView(subTv);
            row.addView(labelCol);

            for (ComparableTrip trip : selected) {
                MetricEntry m = trip.findMetric(combo[0], combo[1], combo[2]);
                TextView cell = new TextView(requireContext());
                cell.setLayoutParams(new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setGravity(Gravity.END);
                cell.setFontFeatureSettings("tnum");
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                cell.setText(m != null ? m.displayValue : "–");
                cell.setTextColor(m != null ? onSurface : onSurfaceVariant);
                row.addView(cell);
            }

            binding.matrixTableContainer.addView(row);
        }
    }

    private void showTripPickerBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        BottomSheetTripPickerBinding sheetBinding = BottomSheetTripPickerBinding.inflate(getLayoutInflater());
        dialog.setContentView(sheetBinding.getRoot());

        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        TripPickerAdapter adapter = new TripPickerAdapter(trip -> viewModel.toggleTripSelection(trip));
        sheetBinding.rvPickerTrips.setLayoutManager(new LinearLayoutManager(requireContext()));
        sheetBinding.rvPickerTrips.setAdapter(adapter);

        sheetBinding.btnPickerDone.setOnClickListener(v -> dialog.dismiss());
        sheetBinding.btnPickerPrevMonth.setOnClickListener(v -> viewModel.previousPickerMonth());
        sheetBinding.btnPickerNextMonth.setOnClickListener(v -> viewModel.nextPickerMonth());
        sheetBinding.btnPickerRefresh.setOnClickListener(v -> viewModel.refreshPickerMonth());

        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault());
        viewModel.pickerMonth.observe(getViewLifecycleOwner(), month -> {
            if (month != null) {
                sheetBinding.tvPickerMonth.setText(month.format(monthFmt));
                boolean canNext = month.isBefore(YearMonth.now());
                sheetBinding.btnPickerNextMonth.setEnabled(canNext);
                sheetBinding.btnPickerNextMonth.setAlpha(canNext ? 1f : 0.35f);
            }
        });

        viewModel.isPickerLoading.observe(getViewLifecycleOwner(), loading ->
                sheetBinding.pickerProgress.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));

        viewModel.pickerTrips.observe(getViewLifecycleOwner(), trips -> {
            List<ComparableTrip> selected = viewModel.selectedTrips.getValue();
            adapter.submitData(trips, selected);
            boolean empty = (trips == null || trips.isEmpty())
                    && !Boolean.TRUE.equals(viewModel.isPickerLoading.getValue());
            sheetBinding.tvPickerEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        });

        viewModel.selectedTrips.observe(getViewLifecycleOwner(), selected -> {
            adapter.submitData(viewModel.pickerTrips.getValue(), selected);
            int count = selected == null ? 0 : selected.size();
            sheetBinding.tvPickerSubtitle.setText(String.format(
                    Locale.getDefault(), "%d of %d selected · Pick 2 to 4 dates",
                    count, CompareTripsViewModel.MAX_SELECTED_TRIPS));
        });

        viewModel.pickerError.observe(getViewLifecycleOwner(), err -> {
            if (err != null) {
                sheetBinding.tvPickerEmpty.setText(err);
                sheetBinding.tvPickerEmpty.setVisibility(View.VISIBLE);
            }
        });

        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}