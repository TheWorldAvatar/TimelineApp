package uk.ac.cam.cares.jps.timeline.ui.adapter;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uk.ac.cam.cares.jps.timeline.model.bottomsheet.Session;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;
import uk.ac.cam.cares.jps.timeline.viewmodel.SegmentClickInterface;
import uk.ac.cam.cares.jps.timelinemap.R;

public class SessionsAdapter extends RecyclerView.Adapter<SessionsAdapter.SessionsViewHolder> {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private List<Session> sessionList;
    private TrajectorySegment clickedSegment;
    private final RecyclerView.RecycledViewPool sharedViewPool = new RecyclerView.RecycledViewPool();
    private final SegmentClickInterface segmentClickInterface;
    private final Map<String, Boolean> expandedBySessionId = new HashMap<>();

    public SessionsAdapter(SegmentClickInterface segmentClickInterface) {
        this.sessionList = new ArrayList<>();
        this.segmentClickInterface = segmentClickInterface;
    }

    public void setUniqueSessionsList(List<Session> sessionList, TrajectorySegment clickedSegment) {
        this.sessionList = new ArrayList<>(sessionList);
        this.clickedSegment = clickedSegment;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SessionsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.unique_session, parent, false);
        return new SessionsViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionsViewHolder holder, int position) {
        Session session = sessionList.get(position);
        holder.sessionTitle.setText(session.getSessionTitle());
        holder.sessionMeta.setText(buildSessionMeta(session));

        boolean isExpanded = !Boolean.FALSE.equals(expandedBySessionId.get(session.getSessionId()));
        holder.activityRecyclerView.setVisibility(isExpanded ? VISIBLE : GONE);
        holder.dropdownIcon.setRotation(isExpanded ? 180f : 0f);

        holder.dropdownLayout.setOnClickListener(view -> {
            boolean nextExpanded = holder.activityRecyclerView.getVisibility() != VISIBLE;
            expandedBySessionId.put(session.getSessionId(), nextExpanded);
            holder.activityRecyclerView.setVisibility(nextExpanded ? VISIBLE : GONE);
            holder.dropdownIcon.animate().rotation(nextExpanded ? 180f : 0f).setDuration(150).start();
        });

        NonScrollableLinearLayoutManager layoutManager =
                new NonScrollableLinearLayoutManager(holder.activityRecyclerView.getContext());
        ActivityItemAdapter activityItemAdapter = new ActivityItemAdapter(
                session.getTrajectorySegments(),
                session.getSessionId(),
                clickedSegment,
                segmentClickInterface
        );
        holder.activityRecyclerView.setLayoutManager(layoutManager);
        holder.activityRecyclerView.setAdapter(activityItemAdapter);
        holder.activityRecyclerView.setRecycledViewPool(sharedViewPool);
        holder.activityRecyclerView.setHasFixedSize(false);
    }

    @Override
    public int getItemCount() {
        return sessionList.size();
    }

    public void setClickedSegment(TrajectorySegment clickedSegment) {
        this.clickedSegment = clickedSegment;
        if (clickedSegment != null) {
            expandedBySessionId.put(clickedSegment.getSessionId(), true);
        }
        notifyDataSetChanged();
    }

    private String buildSessionMeta(Session session) {
        List<TrajectorySegment> segments = session.getTrajectorySegments();
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        long startMs = segments.get(0).getStartTime();
        long endMs = segments.get(segments.size() - 1).getEndTime();
        int totalMeters = 0;
        for (TrajectorySegment seg : segments) {
            if (seg.getStartTime() < startMs) startMs = seg.getStartTime();
            if (seg.getEndTime() > endMs) endMs = seg.getEndTime();
            totalMeters += Math.max(0, seg.getDistanceTraveled());
        }

        ZoneId zone = ZoneId.systemDefault();
        String startStr = Instant.ofEpochMilli(startMs).atZone(zone).format(TIME_FORMATTER);
        String endStr = Instant.ofEpochMilli(endMs).atZone(zone).format(TIME_FORMATTER);

        long totalMinutes = Math.max(1, Math.round((endMs - startMs) / 60000.0));
        String durationStr = formatDuration(totalMinutes);
        String distanceStr = totalMeters >= 1000
                ? String.format(Locale.getDefault(), "%.1f km", totalMeters / 1000.0)
                : totalMeters + " m";

        return startStr + " – " + endStr + " · " + durationStr + " · " + distanceStr;
    }

    private String formatDuration(long totalMinutes) {
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return minutes > 0 ? hours + " hr " + minutes + " min" : hours + " hr";
        }
        return minutes + " min";
    }

    public static class NonScrollableLinearLayoutManager extends LinearLayoutManager {
        public NonScrollableLinearLayoutManager(Context context) {
            super(context, VERTICAL, false);
        }

        @Override
        public boolean canScrollVertically() {
            return false;
        }
    }

    public static class SessionsViewHolder extends RecyclerView.ViewHolder {
        TextView sessionTitle;
        TextView sessionMeta;
        ImageView dropdownIcon;
        RecyclerView activityRecyclerView;
        View dropdownLayout;

        public SessionsViewHolder(@NonNull View sessionView) {
            super(sessionView);
            sessionTitle = sessionView.findViewById(R.id.session_id);
            sessionMeta = sessionView.findViewById(R.id.session_meta);
            dropdownIcon = sessionView.findViewById(R.id.session_dropdown);
            activityRecyclerView = sessionView.findViewById(R.id.activity_items);
            dropdownLayout = sessionView.findViewById(R.id.session_dropdown_layout);
        }
    }
}