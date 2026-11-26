package uk.ac.cam.cares.jps.timeline.ui.bottomsheet;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import uk.ac.cam.cares.jps.timeline.model.bottomsheet.ActivitySummary;
import uk.ac.cam.cares.jps.timeline.model.bottomsheet.Session;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;
import uk.ac.cam.cares.jps.timeline.ui.adapter.ActivitySummaryAdapter;
import uk.ac.cam.cares.jps.timeline.ui.adapter.SessionsAdapter;
import uk.ac.cam.cares.jps.timeline.viewmodel.SegmentClickInterface;
import uk.ac.cam.cares.jps.timelinemap.R;

/**
 * Bottom sheet to be shown when there is no error
 */
public class NormalBottomSheet extends BottomSheet {
    private RecyclerView summaryRecyclerView;
    private ActivitySummaryAdapter summaryAdapter;
    private RecyclerView sessionsRecyclerView;
    private SessionsAdapter sessionsAdapter;
    private SegmentClickInterface segmentClickInterface;

    public NormalBottomSheet(Context context,
                             SegmentClickInterface segmentClickInterface) {
        super(context);
        init(context);
        this.segmentClickInterface = segmentClickInterface;
    }

    @Override
    void init(Context context) {
        bottomSheet = (LinearLayoutCompat) LayoutInflater.from(context).inflate(R.layout.bottom_sheet_widget, null);
        sessionsAdapter = new SessionsAdapter(segmentClickInterface);
        sessionsRecyclerView = bottomSheet.findViewById(R.id.sessions_recycler_view);
        sessionsRecyclerView.setAdapter(sessionsAdapter);
        sessionsRecyclerView.setLayoutManager(new LinearLayoutManager(context));

        // Allow scrolling through all trip segments even when the sheet is Half-Expanded
        sessionsRecyclerView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                    if (rv.canScrollVertically(1) || rv.canScrollVertically(-1)) {
                        rv.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        });

        summaryAdapter = new ActivitySummaryAdapter();
        summaryRecyclerView = bottomSheet.findViewById(R.id.summary_recycler_view);
        summaryRecyclerView.setAdapter(summaryAdapter);
        summaryRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
    }

    /**
     * Adjusts bottom padding of the sessions list to match the off-screen portion
     * of the sheet so the final trip segment always scrolls into view.
     */
    public void updateSessionsBottomPadding(int offscreenBottomPx) {
        if (sessionsRecyclerView == null) return;
        int extraPx = Math.round(24 * sessionsRecyclerView.getResources().getDisplayMetrics().density);
        int bottomPad = Math.max(extraPx, offscreenBottomPx + extraPx);
        if (sessionsRecyclerView.getPaddingBottom() != bottomPad) {
            sessionsRecyclerView.setPadding(
                    sessionsRecyclerView.getPaddingLeft(),
                    sessionsRecyclerView.getPaddingTop(),
                    sessionsRecyclerView.getPaddingRight(),
                    bottomPad
            );
        }
    }

    public void showFetchingAnimation(boolean isFetching) {
        if (isFetching) {
            sessionsAdapter = new SessionsAdapter(segmentClickInterface);
            sessionsRecyclerView.setAdapter(sessionsAdapter);
            summaryAdapter = new ActivitySummaryAdapter();
            summaryRecyclerView.setAdapter(summaryAdapter);
            getBottomSheet().findViewById(R.id.progress_linear).setVisibility(View.VISIBLE);
            getBottomSheet().findViewById(R.id.trajectory_info_tv).setVisibility(View.GONE);
            sessionsRecyclerView.setVisibility(View.GONE);
            summaryRecyclerView.setVisibility(View.GONE);
        } else {
            getBottomSheet().findViewById(R.id.progress_linear).setVisibility(View.GONE);
            summaryRecyclerView.setVisibility(View.VISIBLE);
            sessionsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    public void updateSessionsList(List<Session> sessionList, TrajectorySegment clickedSegment) {
        if (sessionList != null && !sessionList.isEmpty()) {
            sessionsAdapter.setUniqueSessionsList(sessionList, clickedSegment);
            TextView trajectoryTextView = getBottomSheet().findViewById(R.id.trajectory_info_tv);
            if (trajectoryTextView != null) {
                trajectoryTextView.setVisibility(View.GONE);
            }
        } else {
            showEmptyState();
        }
    }

    public void updateSummaryView(List<ActivitySummary> summaryActivityItemList) {
        if (summaryActivityItemList != null && !summaryActivityItemList.isEmpty()) {
            summaryAdapter.setActivityItemList(summaryActivityItemList);
            TextView trajectoryTextView = getBottomSheet().findViewById(R.id.trajectory_info_tv);
            if (trajectoryTextView != null) {
                trajectoryTextView.setVisibility(View.GONE);
            }
        }
    }

    private void showEmptyState() {
        sessionsRecyclerView.setVisibility(View.GONE);
        summaryRecyclerView.setVisibility(View.GONE);
        TextView trajectoryTextView = getBottomSheet().findViewById(R.id.trajectory_info_tv);
        if (trajectoryTextView != null) {
            trajectoryTextView.setText(uk.ac.cam.cares.jps.utils.R.string.trajectoryagent_no_trajectory_found);
            trajectoryTextView.setVisibility(View.VISIBLE);
        }
    }

    public void highlightClickedSegment(TrajectorySegment clickedSegment) {
        sessionsAdapter.setClickedSegment(clickedSegment);
        if (clickedSegment != null) {
            Log.d("SCROLL_DEBUG", "Scrolling to session: " + clickedSegment.getSessionNumber());
            sessionsRecyclerView.post(() -> {
                LinearLayoutManager layoutManager = (LinearLayoutManager) sessionsRecyclerView.getLayoutManager();
                if (layoutManager != null) {
                    layoutManager.scrollToPositionWithOffset(clickedSegment.getSessionNumber() - 1, 0);
                }
            });
        }
    }
}