package uk.ac.cam.cares.jps.timeline.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import uk.ac.cam.cares.jps.timeline.model.bottomsheet.ActivityItem;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;
import uk.ac.cam.cares.jps.timeline.viewmodel.SegmentClickInterface;
import uk.ac.cam.cares.jps.timelinemap.R;

/**
 * Adapter class for recyclerview to view activity segment rows inside a session card.
 */
public class ActivityItemAdapter extends RecyclerView.Adapter<ActivityItemAdapter.ActivityItemViewHolder> {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final List<TrajectorySegment> segmentList;
    private final SegmentClickInterface segmentClickInterface;
    private final String sessionId;
    private final TrajectorySegment clickedSegment;

    public ActivityItemAdapter(List<TrajectorySegment> segmentList,
                               String sessionId,
                               TrajectorySegment clickedSegment,
                               SegmentClickInterface segmentClickInterface) {
        this.segmentList = segmentList;
        this.segmentClickInterface = segmentClickInterface;
        this.sessionId = sessionId;
        this.clickedSegment = clickedSegment;
        for (TrajectorySegment segment : segmentList) {
            ActivityItem item = segment.getActivity();
            boolean isClicked = clickedSegment != null
                    && item.getId() == clickedSegment.getId()
                    && sessionId.equals(clickedSegment.getSessionId());
            item.setClicked(isClicked);
        }
    }

    @NonNull
    @Override
    public ActivityItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.activity_item, parent, false);
        return new ActivityItemViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ActivityItemViewHolder holder, int position) {
        TrajectorySegment segment = segmentList.get(position);
        ActivityItem activityItem = segment.getActivity();
        Context context = holder.itemView.getContext();

        String rawType = segment.getActivityType() != null
                ? segment.getActivityType().toLowerCase(Locale.ROOT)
                : "";

        int modeColor = getModeColor(rawType);
        holder.activityType.setImageResource(activityItem.getActivityImage());
        holder.activityType.setImageTintList(ColorStateList.valueOf(modeColor));

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(ColorUtils.setAlphaComponent(modeColor, 34));
        holder.activityType.setBackground(badgeBg);

        holder.activityLabel.setText(getModeLabel(rawType, segment.getTrip()));
        holder.activityMeta.setText(formatSegmentMeta(segment));
        holder.timeSummary.setText(formatTimeRange(segment.getStartTime(), segment.getEndTime()));

        boolean isSelected = activityItem.getClicked();
        int primaryColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#00687C"));
        int outlineColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#CBD5E1"));
        int normalSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.parseColor("#F8FAFC"));

        if (isSelected) {
            holder.segmentCard.setStrokeColor(primaryColor);
            holder.segmentCard.setStrokeWidth(dp(context, 1.5f));
            holder.segmentCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(primaryColor, 26));
        } else {
            holder.segmentCard.setStrokeColor(outlineColor);
            holder.segmentCard.setStrokeWidth(dp(context, 1f));
            holder.segmentCard.setCardBackgroundColor(normalSurface);
        }

        holder.segmentCard.setOnClickListener(v ->
                segmentClickInterface.setClickedSegment(activityItem.getId(), sessionId));
    }

    @Override
    public int getItemCount() {
        return segmentList.size();
    }

    public int getFirstClickedItemPosition() {
        for (int i = 0; i < segmentList.size(); i++) {
            if (segmentList.get(i).getActivity().getClicked()) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    private int getModeColor(String type) {
        switch (type) {
            case "walking":
                return Color.parseColor("#16A34A");
            case "vehicle":
                return Color.parseColor("#2563EB");
            case "bike":
                return Color.parseColor("#D97706");
            case "still":
                return Color.parseColor("#D9483B");
            default:
                return Color.parseColor("#64748B");
        }
    }

    private String getModeLabel(String type, int trip) {
        switch (type) {
            case "walking":
                return "Walking";
            case "vehicle":
                return "Bus / Vehicle";
            case "bike":
                return "Cycling";
            case "still":
                return "Stationary";
            case "lost_signal":
                return "Lost signal";
            default:
                return trip == 0 ? "Stay" : "Trip Segment";
        }
    }

    private String formatSegmentMeta(TrajectorySegment segment) {
        long minutes = Math.max(1, Math.round((segment.getEndTime() - segment.getStartTime()) / 60000.0));
        String durationStr = minutes >= 60
                ? (minutes / 60) + " hr " + (minutes % 60) + " min"
                : minutes + " min";
        int meters = Math.max(0, segment.getDistanceTraveled());
        String distanceStr = meters >= 1000
                ? String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
                : meters + " m";
        return durationStr + " · " + distanceStr;
    }

    private String formatTimeRange(long startMs, long endMs) {
        ZoneId zone = ZoneId.systemDefault();
        String start = Instant.ofEpochMilli(startMs).atZone(zone).format(TIME_FORMATTER);
        String end = Instant.ofEpochMilli(endMs).atZone(zone).format(TIME_FORMATTER);
        return start + "–" + end;
    }

    private int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class ActivityItemViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView segmentCard;
        ImageView activityType;
        TextView activityLabel;
        TextView activityMeta;
        TextView timeSummary;

        public ActivityItemViewHolder(@NonNull View activityView) {
            super(activityView);
            segmentCard = activityView.findViewById(R.id.segment_card);
            activityType = activityView.findViewById(R.id.activityType);
            activityLabel = activityView.findViewById(R.id.activityLabel);
            activityMeta = activityView.findViewById(R.id.activityMeta);
            timeSummary = activityView.findViewById(R.id.timeSummary);
        }
    }
}