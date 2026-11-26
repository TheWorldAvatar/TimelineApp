package uk.ac.cam.cares.jps.user.exposure;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import uk.ac.cam.cares.jps.user.databinding.ItemExposureDayBinding;

public class ExposureDayAdapter extends RecyclerView.Adapter<ExposureDayAdapter.DayViewHolder> {

    public interface Listener {
        void onViewOnMap(LocalDate date);
        void onCalculate(LocalDate date);
    }

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault());

    private final Listener listener;
    private final List<ExposureOverviewViewModel.DaySummary> items = new ArrayList<>();
    private final Set<LocalDate> expandedDates = new HashSet<>();

    public ExposureDayAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<ExposureOverviewViewModel.DaySummary> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemExposureDayBinding binding = ItemExposureDayBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new DayViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull DayViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class DayViewHolder extends RecyclerView.ViewHolder {
        private final ItemExposureDayBinding b;

        DayViewHolder(ItemExposureDayBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(ExposureOverviewViewModel.DaySummary day) {
            Context ctx = b.getRoot().getContext();
            b.tvDate.setText(day.date.format(DATE_FORMAT));

            if (day.status == ExposureOverviewViewModel.DayStatus.CALCULATED) {
                b.tvStatus.setText("Calculated");
                b.tvStatus.setTextColor(MaterialColors.getColor(
                        b.tvStatus, com.google.android.material.R.attr.colorPrimary));
            } else if (day.status == ExposureOverviewViewModel.DayStatus.RUNNING) {
                b.tvStatus.setText("Running");
                b.tvStatus.setTextColor(MaterialColors.getColor(
                        b.tvStatus, com.google.android.material.R.attr.colorTertiary));
            } else {
                b.tvStatus.setText("Not calculated");
                b.tvStatus.setTextColor(MaterialColors.getColor(
                        b.tvStatus, com.google.android.material.R.attr.colorOnSurfaceVariant));
            }

            if (day.configs.isEmpty()) {
                b.tvSummaryDatasets.setText("Trajectory recorded · No exposures calculated");
                b.tvSummaryBuffers.setVisibility(View.GONE);
            } else {
                Set<String> datasets = new LinkedHashSet<>();
                Set<String> types = new LinkedHashSet<>();
                Set<String> buffers = new LinkedHashSet<>();
                for (ExposureOverviewViewModel.ConfigSummary cfg : day.configs) {
                    datasets.add(cfg.dataset);
                    types.add(cfg.calcType);
                    buffers.addAll(cfg.buffers);
                }
                b.tvSummaryDatasets.setText(
                        day.tripOrStayCount + " segments · " + String.join(", ", datasets));
                b.tvSummaryBuffers.setVisibility(View.VISIBLE);
                b.tvSummaryBuffers.setText(
                        String.join(" · ", types) + " · " + String.join(", ", buffers));
            }

            boolean expanded = expandedDates.contains(day.date);
            b.expandedContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            b.ivExpand.setRotation(expanded ? 180f : 0f);

            b.rowHeader.setOnClickListener(v -> {
                if (expandedDates.contains(day.date)) {
                    expandedDates.remove(day.date);
                } else {
                    expandedDates.add(day.date);
                }
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos);
                }
            });

            if (expanded) {
                populateBreakdown(ctx, day);
            }

            b.btnViewOnMap.setOnClickListener(v -> listener.onViewOnMap(day.date));

            b.btnCalculate.setEnabled(day.status != ExposureOverviewViewModel.DayStatus.RUNNING);
            if (day.status == ExposureOverviewViewModel.DayStatus.RUNNING) {
                b.btnCalculate.setText("Running…");
            } else if (day.status == ExposureOverviewViewModel.DayStatus.CALCULATED) {
                b.btnCalculate.setText("Recalculate");
            } else {
                b.btnCalculate.setText("Calculate");
            }
            b.btnCalculate.setOnClickListener(v -> listener.onCalculate(day.date));
        }

        private void populateBreakdown(Context ctx, ExposureOverviewViewModel.DaySummary day) {
            b.breakdownRows.removeAllViews();
            if (day.configs.isEmpty()) {
                b.breakdownHeader.setVisibility(View.GONE);
                b.tvMissingHint.setVisibility(View.VISIBLE);
                return;
            }
            b.breakdownHeader.setVisibility(View.VISIBLE);
            b.tvMissingHint.setVisibility(View.GONE);

            int primaryColor = MaterialColors.getColor(
                    b.breakdownRows, com.google.android.material.R.attr.colorOnSurface);
            int secondaryColor = MaterialColors.getColor(
                    b.breakdownRows, com.google.android.material.R.attr.colorOnSurfaceVariant);

            float density = ctx.getResources().getDisplayMetrics().density;
            int padV = Math.round(6 * density);

            for (ExposureOverviewViewModel.ConfigSummary cfg : day.configs) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, padV, 0, padV);

                TextView col1 = new TextView(ctx);
                col1.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f));
                col1.setText(cfg.dataset + "\n" + cfg.calcType);
                col1.setTextSize(12f);
                col1.setTextColor(primaryColor);

                TextView col2 = new TextView(ctx);
                col2.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f));
                col2.setText(String.join(", ", cfg.buffers));
                col2.setTextSize(12f);
                col2.setTextColor(secondaryColor);

                TextView col3 = new TextView(ctx);
                col3.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
                col3.setGravity(android.view.Gravity.END);
                col3.setText(String.valueOf(cfg.segmentCount));
                col3.setTextSize(12f);
                col3.setTextColor(primaryColor);

                row.addView(col1);
                row.addView(col2);
                row.addView(col3);
                b.breakdownRows.addView(row);
            }
        }
    }
}