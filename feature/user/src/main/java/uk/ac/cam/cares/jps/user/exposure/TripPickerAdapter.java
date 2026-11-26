package uk.ac.cam.cares.jps.user.exposure;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

import uk.ac.cam.cares.jps.user.databinding.ItemPickerTripBinding;
import uk.ac.cam.cares.jps.user.exposure.CompareTripsViewModel.ComparableTrip;

public class TripPickerAdapter extends RecyclerView.Adapter<TripPickerAdapter.Holder> {

    public interface OnTripClickListener {
        void onTripClicked(ComparableTrip trip);
    }

    public static final int[] SERIES_COLORS = {
            0xFF00687C, // A
            0xFFD9483B, // B
            0xFF7C3AED, // C
            0xFFD97706  // D
    };
    public static final String[] SLOT_LETTERS = {"A", "B", "C", "D"};

    private final List<ComparableTrip> trips = new ArrayList<>();
    private final List<String> selectedIds = new ArrayList<>();
    private final OnTripClickListener listener;

    public TripPickerAdapter(OnTripClickListener listener) {
        this.listener = listener;
    }

    public void submitData(List<ComparableTrip> newTrips, List<ComparableTrip> selectedTrips) {
        trips.clear();
        if (newTrips != null) trips.addAll(newTrips);

        selectedIds.clear();
        if (selectedTrips != null) {
            for (ComparableTrip t : selectedTrips) {
                selectedIds.add(t.id);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPickerTripBinding binding = ItemPickerTripBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ComparableTrip trip = trips.get(position);
        int selectedIdx = selectedIds.indexOf(trip.id);
        boolean isSelected = selectedIdx >= 0;
        boolean canSelectMore = selectedIds.size() < CompareTripsViewModel.MAX_SELECTED_TRIPS;

        holder.binding.tvTripTitle.setText(trip.title);
        holder.binding.tvTripMeta.setText(trip.formattedSummaryLine());
        holder.binding.tvTripDatasets.setText(String.join(" · ", trip.datasetNames()));

        float dp = holder.itemView.getResources().getDisplayMetrics().density;
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(10f * dp);

        int primaryColor = MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorPrimary, 0xFF00687C);
        int outlineColor = MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorOutline, 0xFF899295);
        int surfaceVariant = MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE5EBEE);
        int onSurfaceVariant = MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF475569);

        if (isSelected) {
            int seriesColor = SERIES_COLORS[selectedIdx % SERIES_COLORS.length];
            badgeBg.setColor(seriesColor);
            holder.binding.tvSlotBadge.setBackground(badgeBg);
            holder.binding.tvSlotBadge.setText(SLOT_LETTERS[selectedIdx]);
            holder.binding.tvSlotBadge.setTextColor(Color.WHITE);

            holder.binding.cardPickerTrip.setStrokeColor(seriesColor);
            holder.binding.cardPickerTrip.setStrokeWidth(Math.round(2f * dp));
            holder.binding.tvSelectionState.setText("Selected");
            holder.binding.tvSelectionState.setTextColor(seriesColor);
            holder.itemView.setAlpha(1f);
        } else {
            badgeBg.setColor(surfaceVariant);
            holder.binding.tvSlotBadge.setBackground(badgeBg);
            holder.binding.tvSlotBadge.setText("+");
            holder.binding.tvSlotBadge.setTextColor(onSurfaceVariant);

            holder.binding.cardPickerTrip.setStrokeColor(outlineColor);
            holder.binding.cardPickerTrip.setStrokeWidth(Math.round(1f * dp));
            holder.binding.tvSelectionState.setText(canSelectMore ? "Add" : "Max 4");
            holder.binding.tvSelectionState.setTextColor(canSelectMore ? primaryColor : onSurfaceVariant);
            holder.itemView.setAlpha(canSelectMore ? 1f : 0.5f);
        }
    }

    @Override
    public int getItemCount() {
        return trips.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        final ItemPickerTripBinding binding;

        Holder(ItemPickerTripBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.cardPickerTrip.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                listener.onTripClicked(trips.get(pos));
            });
        }
    }
}