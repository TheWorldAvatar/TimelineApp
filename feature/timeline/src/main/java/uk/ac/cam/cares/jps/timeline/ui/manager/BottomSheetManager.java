package uk.ac.cam.cares.jps.timeline.ui.manager;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.widget.TextView;
import android.widget.ImageButton;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.GestureDetector;
import android.view.MotionEvent;
import androidx.core.widget.NestedScrollView;
import androidx.appcompat.widget.LinearLayoutCompat;
import android.widget.Toast;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.apache.log4j.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import uk.ac.cam.cares.jps.login.AccountException;
import uk.ac.cam.cares.jps.sensor.source.state.SensorCollectionStateException;
import uk.ac.cam.cares.jps.timeline.model.bottomsheet.ActivitySummary;
import uk.ac.cam.cares.jps.timeline.model.bottomsheet.Session;
import uk.ac.cam.cares.jps.timeline.ui.bottomsheet.BottomSheet;
import uk.ac.cam.cares.jps.timeline.ui.bottomsheet.ErrorBottomSheet;
import uk.ac.cam.cares.jps.timeline.ui.bottomsheet.NormalBottomSheet;
import uk.ac.cam.cares.jps.timeline.ui.datepicker.GreyOutDecorator;
import uk.ac.cam.cares.jps.timeline.viewmodel.ConnectionViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.TrajectoryViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.UserPhoneViewModel;
import uk.ac.cam.cares.jps.timelinemap.R;

import android.view.View;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;

import android.app.Activity;
import uk.ac.cam.cares.jps.data.ExposureFeatureInfoRepository;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

import org.json.JSONArray;
import org.json.JSONObject;
import uk.ac.cam.cares.jps.timeline.viewmodel.ExposureFeatureInfoViewModel;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONException;


/**
 * An UI manager that manages bottom sheets on screen and switches in between different bottom sheets
 * based on the state.
 */
public class BottomSheetManager {
    private final TrajectoryViewModel trajectoryViewModel;
    private final ConnectionViewModel connectionViewModel;
    private final UserPhoneViewModel userPhoneViewModel;
    private final NormalBottomSheetViewModel normalBottomSheetViewModel;

    private Logger LOGGER = Logger.getLogger(BottomSheetManager.class);

    private FragmentManager fragmentManager;
    private final BottomSheetBehavior<LinearLayoutCompat> bottomSheetBehavior;
    private final LinearLayoutCompat bottomSheetContainer;

    private final LifecycleOwner lifecycleOwner;
    private final Context context;

    private NormalBottomSheet normalBottomSheet;
    private ErrorBottomSheet errorBottomSheet;
    private final MaterialAlertDialogBuilder sessionExpiredDialog;
    private GreyOutDecorator greyOutDecorator;

    private final View tripDetailBubble;
    private final TextView tripDetailIdTv;
    private final TextView tripDetailDistanceTv;

    private final ImageButton tripDetailPrevBt;
    private final ImageButton tripDetailNextBt;
    private final TextView tripDetailPageIndicatorTv;

    private float swipeStartX = 0f;
    private float swipeStartY = 0f;
    private static final int SWIPE_THRESHOLD = 100;

    private final List<Map.Entry<String, CharSequence>> resultPages = new ArrayList<>();
    private int currentPageIndex = 0;

    // field, alongside the other ViewModels
    private final ExposureFeatureInfoViewModel exposureFeatureInfoViewModel;

    // track which trip is currently displayed, so a stale response can't overwrite a newer click
    private TrajectorySegment pendingSegment;

    private final NestedScrollView tripDetailScroll;

    /**
     * Constructor of the class
     *
     * @param fragment             Fragment that hosts the bottom sheet
     * @param bottomSheetContainer Container of the bottom sheet
     */
    public BottomSheetManager(Fragment fragment, LinearLayoutCompat bottomSheetContainer) {

        trajectoryViewModel = new ViewModelProvider(fragment).get(TrajectoryViewModel.class);
        connectionViewModel = new ViewModelProvider(fragment).get(ConnectionViewModel.class);
        userPhoneViewModel = new ViewModelProvider(fragment).get(UserPhoneViewModel.class);
        normalBottomSheetViewModel = new ViewModelProvider(fragment).get(NormalBottomSheetViewModel.class);
        exposureFeatureInfoViewModel = new ViewModelProvider(fragment).get(ExposureFeatureInfoViewModel.class);

        lifecycleOwner = fragment.getViewLifecycleOwner();
        context = fragment.requireContext();

        fragmentManager = fragment.getParentFragmentManager();
        sessionExpiredDialog = userPhoneViewModel.getSessionExpiredDialog(fragment);

        this.bottomSheetContainer = bottomSheetContainer;
        this.bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetContainer);
        greyOutDecorator = new GreyOutDecorator();

        

        View rootView = fragment.requireView();
        tripDetailBubble = rootView.findViewById(R.id.trip_detail_bubble);
        tripDetailIdTv = rootView.findViewById(R.id.trip_detail_id_tv);
        tripDetailDistanceTv = rootView.findViewById(R.id.trip_detail_distance_tv);
        tripDetailBubble.findViewById(R.id.trip_detail_close_bt)
                .setOnClickListener(v -> trajectoryViewModel.removeAllClicked());
        tripDetailScroll = tripDetailBubble.findViewById(R.id.trip_detail_scroll);

        tripDetailPrevBt = tripDetailBubble.findViewById(R.id.trip_detail_prev_bt);
        tripDetailNextBt = tripDetailBubble.findViewById(R.id.trip_detail_next_bt);
        tripDetailPageIndicatorTv = tripDetailBubble.findViewById(R.id.trip_detail_page_indicator_tv);

        tripDetailPrevBt.setOnClickListener(v -> showPreviousPage());
        tripDetailNextBt.setOnClickListener(v -> showNextPage());

        tripDetailBubble.setOnTouchListener((v, event) -> handleSwipeTouch(event));

        tripDetailScroll.setOnTouchListener((v, event) -> handleSwipeTouch(event));

        tripDetailDistanceTv.setOnTouchListener((v, event) -> handleSwipeTouch(event));

        initBottomSheet();
    }

    private void initBottomSheet() {
        initNormalBottomSheet();
        initErrorBottomSheet();

        connectionViewModel.getHasConnection().observe(lifecycleOwner, hasConnection -> {
            if (hasConnection) {
                setBottomSheet(normalBottomSheet);
                trajectoryViewModel.getTrajectory(normalBottomSheetViewModel.selectedDate.getValue());
            } else {
                errorBottomSheet.setErrorType(ErrorBottomSheet.ErrorType.CONNECTION_ERROR);
                setAndExtendBottomSheet(errorBottomSheet);
            }
        });
        connectionViewModel.checkNetworkConnection();
    }

    private void initNormalBottomSheet() {
        normalBottomSheet = new NormalBottomSheet(context, trajectoryViewModel);
        configureDateSelection();
        configureTrajectoryRetrieval();
        configureSummary();
        configureExposureFeatureInfo();
    }

    private void configureTrajectoryRetrieval() {
        trajectoryViewModel.isFetchingTrajectory.observe(lifecycleOwner, normalBottomSheet::showFetchingAnimation);
    }


    private void configureSummary() {

        trajectoryViewModel.trajectory.observe(lifecycleOwner, trajectoryByDate -> {
            List<ActivitySummary> activityItemSummaryList = trajectoryByDate.getActivitySummary();
            List<Session> uniqueSessions = trajectoryByDate.getSessions();

            if (trajectoryByDate.getDate().equals(normalBottomSheetViewModel.selectedDate.getValue())) {
                normalBottomSheet.updateSummaryView(activityItemSummaryList);

                // no clickedSegment when trajectory just loaded
                normalBottomSheet.updateSessionsList(uniqueSessions, null);
            }
        });

        trajectoryViewModel.clickedSegment.observe(lifecycleOwner, clickedId -> {
            normalBottomSheet.highlightClickedSegment(clickedId);
            updateTripDetailBubble(clickedId);
        });
    }

    private void configureExposureFeatureInfo() {
        exposureFeatureInfoViewModel.timelineResults.observe(lifecycleOwner, json -> {
            if (json == null || pendingSegment == null) return;
            resultPages.clear();
            try {
                JSONObject group = findMatchingGroup(new JSONArray(json), pendingSegment);
                if (group != null) {
                    tripDetailIdTv.setText(formatTripKey(group.getString("key")));
                    buildResultPages(group.getJSONObject("results"));
                } else {
                    tripDetailIdTv.setText("");
                }
            } catch (JSONException e) {
                LOGGER.error("Failed to parse exposure timeline results: " + e.getMessage(), e);
            }
            currentPageIndex = 0;
            showCurrentPage();
        });
        exposureFeatureInfoViewModel.error.observe(lifecycleOwner, error -> {
            if (error != null) {
                resultPages.clear();
                showCurrentPage();
            }
        });
    }

    private void buildResultPages(JSONObject results) throws JSONException {
        Iterator<String> datasetKeys = results.keys();
        while (datasetKeys.hasNext()) {
            String dataset = datasetKeys.next();          // "Sports facility"
            JSONObject calcs = results.getJSONObject(dataset); // {"Trajectory count": {...}}
            resultPages.add(new AbstractMap.SimpleEntry<>(dataset, renderDatasetBody(dataset, calcs)));
        }
    }

    private CharSequence renderDatasetBody(String datasetName, JSONObject calcs) throws JSONException {
        SpannableStringBuilder builder = new SpannableStringBuilder();

        int titleStart = builder.length();

        Iterator<String> calcKeys = calcs.keys();
        while (calcKeys.hasNext()) {
            String calc = calcKeys.next();
            JSONObject values = calcs.getJSONObject(calc);

            int headerStart = builder.length();
            builder.append(calc).append("\n");
            builder.setSpan(new StyleSpan(Typeface.BOLD), headerStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(1.1f), headerStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            List<String> parts = new ArrayList<>();
            Iterator<String> distanceKeys = values.keys();
            while (distanceKeys.hasNext()) {
                String distanceKey = distanceKeys.next();
                if (distanceKey.equals("collapse")) continue;
                parts.add(distanceKey + ": " + cleanValue(values.getString(distanceKey)));
            }
            builder.append(String.join(", ", parts)).append("\n\n");
        }

        // trim trailing blank line
        while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '\n') {
            builder.delete(builder.length() - 1, builder.length());
        }
        return builder;
    }

    private void showCurrentPage() {
        boolean hasPages = !resultPages.isEmpty();
        tripDetailPrevBt.setEnabled(hasPages);
        tripDetailNextBt.setEnabled(hasPages);

        if (!hasPages) {
            tripDetailDistanceTv.setText("No exposure data");
            tripDetailPageIndicatorTv.setText("");
            return;
        }

        Map.Entry<String, CharSequence> page = resultPages.get(currentPageIndex);
        tripDetailDistanceTv.setText(page.getValue());
        tripDetailPageIndicatorTv.setText(page.getKey());
    }

    private void updateTripDetailBubble(TrajectorySegment clickedSegment) {
        if (clickedSegment != null) {
            tripDetailIdTv.setText("Loading…");
            tripDetailDistanceTv.setText("");
            tripDetailBubble.setVisibility(View.VISIBLE);

            pendingSegment = clickedSegment;
            exposureFeatureInfoViewModel.getTimelineResults(clickedSegment.getStartTime(), clickedSegment.getEndTime());
        } else {
            tripDetailBubble.setVisibility(View.GONE);
        }
    }

    private JSONObject findMatchingGroup(JSONArray groups, TrajectorySegment segment) throws JSONException {
        int tripIndex = segment.getTrip();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            if (group.getInt("trip") != tripIndex) continue;

            if (tripIndex == 0) {
                Instant groupStart = Instant.parse(group.getString("lowerbound"));
                Instant groupEnd = Instant.parse(group.getString("upperbound"));
                Instant segmentStart = Instant.ofEpochMilli(segment.getStartTime());
                Instant segmentEnd = Instant.ofEpochMilli(segment.getEndTime());
                boolean overlaps = !groupEnd.isBefore(segmentStart) && !groupStart.isAfter(segmentEnd);
                if (!overlaps) continue;
            }
            return group;
        }
        return null;
    }


    private void configureDateSelection() {
        normalBottomSheet.getBottomSheet().findViewById(R.id.date_left_bt).setOnClickListener(view ->
                normalBottomSheetViewModel.setToLastDate());

        normalBottomSheet.getBottomSheet().findViewById(R.id.date_right_bt).setOnClickListener(view ->
                normalBottomSheetViewModel.setToNextDate());

        normalBottomSheet.getBottomSheet().findViewById(R.id.date_picker_layout).setOnClickListener(view ->
                getDatePicker(normalBottomSheetViewModel).show(fragmentManager, "date_picker"));

        normalBottomSheetViewModel.selectedDate.observe(lifecycleOwner, selectedDate -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("E, MMMM dd, yyyy");
            ((TextView) normalBottomSheet.getBottomSheet().findViewById(R.id.date_tv)).setText(selectedDate.format(formatter));
            connectionViewModel.checkNetworkConnection();
        });
        normalBottomSheetViewModel.datesWithTrajectory.observe(lifecycleOwner, dates -> greyOutDecorator.setDatesWithTrajectory(dates));
        normalBottomSheetViewModel.getDatesWithTrajectory(ZonedDateTime.now(ZoneId.systemDefault()).toOffsetDateTime().getOffset().getId());
    }

    private MaterialDatePicker<Long> getDatePicker(NormalBottomSheetViewModel normalBottomSheetViewModel) {
        MaterialDatePicker<Long> datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.select_date)
                .setSelection(normalBottomSheetViewModel.getSelectedDateLong())
                .setDayViewDecorator(greyOutDecorator)
                .build();
        datePicker.addOnPositiveButtonClickListener(o -> normalBottomSheetViewModel.setDate(Instant.ofEpochMilli(o).atZone(ZoneId.of("UTC")).toLocalDate()));
        return datePicker;
    }

    private void initErrorBottomSheet() {
        errorBottomSheet = new ErrorBottomSheet(context, connectionViewModel, userPhoneViewModel);
        userPhoneViewModel.getError().observe(lifecycleOwner, error -> {
            if (error == null) {
                return;
            }

            if (error instanceof AccountException) {
                // session expired
                sessionExpiredDialog.show();
            } else if (error instanceof SensorCollectionStateException) {
                // retry getting user id, device id and registration
                errorBottomSheet.setErrorType(ErrorBottomSheet.ErrorType.ACCOUNT_ERROR);
            }
        });

        trajectoryViewModel.trajectoryError.observe(lifecycleOwner, error -> {
            if (error == null) {
                return;
            }

            if (error instanceof AccountException) {
                // retry register phone to user
                errorBottomSheet.setErrorType(ErrorBottomSheet.ErrorType.ACCOUNT_ERROR);
            } else {
                LOGGER.error("error in trajectory retrieval: " + error.getMessage(), error);
                errorBottomSheet.setErrorType(ErrorBottomSheet.ErrorType.TRAJECTORY_ERROR);
            }
            setAndExtendBottomSheet(errorBottomSheet);
        });
    }

    private void setBottomSheet(BottomSheet bottomSheet) {
        bottomSheetContainer.removeAllViews();
        bottomSheetContainer.addView(bottomSheet.getBottomSheet(), MATCH_PARENT, MATCH_PARENT);
    }

    private void setAndExtendBottomSheet(BottomSheet bottomSheet) {
        setBottomSheet(bottomSheet);
        if (bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED &&
                bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_DRAGGING &&
                bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_SETTLING) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
        }
    }

    private String formatTripKey(String key) {
        if (key == null || key.isEmpty()) return key;
        String withSpace = key.replace("-", " ");
        return Character.toUpperCase(withSpace.charAt(0)) + withSpace.substring(1);
    }

    private String cleanValue(String rawValue) {
        // Strips a trailing unit bracket like " [-]" or " [m]"
        return rawValue.replaceAll("\\s*\\[.*?]\\s*$", "").trim();
    }

    private void showNextPage() {
        if (resultPages.isEmpty()) return;
        currentPageIndex = (currentPageIndex + 1) % resultPages.size();
        showCurrentPage();
    }

    private void showPreviousPage() {
        if (resultPages.isEmpty()) return;
        currentPageIndex = (currentPageIndex - 1 + resultPages.size()) % resultPages.size();
        showCurrentPage();
    }

    private boolean handleSwipeTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartX = event.getX();
                swipeStartY = event.getY();
                tripDetailScroll.requestDisallowInterceptTouchEvent(true);
                return true; // must consume DOWN or MOVE/UP never arrive

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float diffX = event.getX() - swipeStartX;
                float diffY = event.getY() - swipeStartY;
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD) {
                    if (diffX < 0) {
                        showNextPage();
                    } else {
                        showPreviousPage();
                    }
                }
                tripDetailScroll.requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return false;
    }
}

