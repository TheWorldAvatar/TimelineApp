package uk.ac.cam.cares.jps.timeline.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import uk.ac.cam.cares.jps.timeline.model.bottomsheet.ActivitySummary;
import uk.ac.cam.cares.jps.timelinemap.R;

public class ActivitySummaryAdapter extends RecyclerView.Adapter<ActivitySummaryAdapter.ActivitySummaryViewHolder> {
    private static final List<String> VALID_ACTIVITIES = Arrays.asList("walking", "vehicle", "bike", "still");
    private List<ActivitySummary> summaryList;

    public ActivitySummaryAdapter() {
        this.summaryList = new ArrayList<>();
    }

    public void setActivityItemList(List<ActivitySummary> summaryList) {
        List<ActivitySummary> filtered = new ArrayList<>();
        if (summaryList != null) {
            for (ActivitySummary item : summaryList) {
                if (item != null && VALID_ACTIVITIES.contains(item.getActivityType())) {
                    filtered.add(item);
                }
            }
        }
        this.summaryList = filtered;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ActivitySummaryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.summary_info, parent, false);
        return new ActivitySummaryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ActivitySummaryViewHolder holder, int position) {
        ActivitySummary summaryItem = summaryList.get(position);
        holder.activityType.setImageResource(summaryItem.getActivityImage());
        holder.activityLabel.setText(formatLabel(summaryItem.getActivityType()));
        holder.totalDistance.setText(formatDistance(summaryItem.getTotalDistance()));
        holder.timeSummary.setText(summaryItem.getTimeSummary());
    }

    @Override
    public int getItemCount() {
        return summaryList.size();
    }

    private String formatLabel(String type) {
        if (type == null) return "";
        switch (type) {
            case "walking":
                return "Walk";
            case "vehicle":
                return "Vehicle";
            case "bike":
                return "Cycle";
            case "still":
                return "Stay";
            default:
                return type;
        }
    }

    private String formatDistance(String rawDistance) {
        if (rawDistance == null) return "0 m";
        String trimmed = rawDistance.trim();
        if (trimmed.endsWith(" m")) {
            try {
                int meters = Integer.parseInt(trimmed.substring(0, trimmed.length() - 2).trim());
                if (meters >= 1000) {
                    return String.format(Locale.getDefault(), "%.1f km", meters / 1000.0);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return trimmed;
    }

    static class ActivitySummaryViewHolder extends RecyclerView.ViewHolder {
        ImageView activityType;
        TextView activityLabel;
        TextView totalDistance;
        TextView timeSummary;

        public ActivitySummaryViewHolder(@NonNull View summaryView) {
            super(summaryView);
            activityType = summaryView.findViewById(R.id.activity_type);
            activityLabel = summaryView.findViewById(R.id.activity_label);
            totalDistance = summaryView.findViewById(R.id.total_distance);
            timeSummary = summaryView.findViewById(R.id.total_time);
        }
    }
}