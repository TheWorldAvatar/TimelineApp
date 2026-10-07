package uk.ac.cam.cares.jps.timeline.ui.manager;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uk.ac.cam.cares.jps.login.AccountException;
import uk.ac.cam.cares.jps.sensor.source.state.SensorCollectionStateException;
import uk.ac.cam.cares.jps.timeline.model.bottomsheet.ActivitySummary;
import uk.ac.cam.cares.jps.timeline.model.bottomsheet.Session;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;
import uk.ac.cam.cares.jps.timeline.ui.bottomsheet.BottomSheet;
import uk.ac.cam.cares.jps.timeline.ui.bottomsheet.ErrorBottomSheet;
import uk.ac.cam.cares.jps.timeline.ui.bottomsheet.NormalBottomSheet;
import uk.ac.cam.cares.jps.timeline.ui.datepicker.GreyOutDecorator;
import uk.ac.cam.cares.jps.timeline.viewmodel.ConnectionViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.ExposureFeatureInfoViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.TrajectoryViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.UserPhoneViewModel;
import uk.ac.cam.cares.jps.timelinemap.R;

/**
 * An UI manager that manages bottom sheets on screen and switches in between different bottom sheets
 * based on the state.
 */
public class BottomSheetManager {
    private final TrajectoryViewModel trajectoryViewModel;
    private final ConnectionViewModel connectionViewModel;
    private final UserPhoneViewModel userPhoneViewModel;
    private final NormalBottomSheetViewModel normalBottomSheetViewModel;
    private final ExposureFeatureInfoViewModel exposureFeatureInfoViewModel;

    private final Logger LOGGER = Logger.getLogger(BottomSheetManager.class);

    private final FragmentManager fragmentManager;
    private final BottomSheetBehavior<LinearLayoutCompat> bottomSheetBehavior;
    private final LinearLayoutCompat bottomSheetContainer;

    private final LifecycleOwner lifecycleOwner;
    private final Context context;

    private NormalBottomSheet normalBottomSheet;
    private ErrorBottomSheet errorBottomSheet;
    private final MaterialAlertDialogBuilder sessionExpiredDialog;
    private final GreyOutDecorator greyOutDecorator;

    // trip detail bubble
    private final View tripDetailBubble;
    private final TextView tripDetailIdTv;
    private final TextView tripDetailDistanceTv;          // now only shows "Loading…" / "No exposure data"
    private final View tripDetailTableHeaderGroup;        // NEW: pinned header + divider
    private final LinearLayout tripDetailTableHeader;     // NEW: column titles
    private final LinearLayout tripDetailTableRows;       // NEW: one row per distance
    private final NestedScrollView tripDetailScroll;
    private final ImageButton tripDetailPrevBt;
    private final ImageButton tripDetailNextBt;
    private final TextView tripDetailPageIndicatorTv;

    // CHANGED: one parsed page per dataset (was a pre-rendered CharSequence)
    private final List<ResultPage> resultPages = new ArrayList<>();
    private int currentPageIndex = 0;

    // track which trip is currently displayed, so a stale response can't overwrite a newer click
    private TrajectorySegment pendingSegment;

    // swipe / scroll handling
    private static final int AXIS_NONE = 0, AXIS_HORIZONTAL = 1, AXIS_VERTICAL = 2;
    // vertical movement must be this many times larger than horizontal to count as a scroll;
    // raise it to make horizontal swipes easier, lower it to make scrolling easier
    private static final float VERTICAL_BIAS = 1.5f;
    private int axisLock = AXIS_NONE;
    private float swipeStartX = 0f;
    private float swipeStartY = 0f;
    private float lastTouchY = 0f;
    private boolean scrolledVertically = false;
    private int touchSlop;
    private int swipeThresholdPx;
    // NEW: field (was a local) so the table cells created later can use the same listener
    private final View.OnTouchListener swipeListener;

    // matches a leading number plus optional unit in a distance key,
    // e.g. "400 m", "800 m", "1.5 km", "500m". Group 1 = number, group 2 = unit.
    private static final Pattern DISTANCE_KEY_PATTERN =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(km|m)?", Pattern.CASE_INSENSITIVE);

    private static final String EMPTY_CELL = "–";

    /**
     * NEW: everything needed to draw one dataset as a table.
     * Columns = calculation types, rows = distances (ascending).
     */
    private static final class ResultPage {
        final String dataset;
        final List<String> calcNames;     // column titles
        final List<String> distances;     // row labels, already sorted ascending
        final List<String[]> rows;        // rows.get(i)[c] = value of calc c at distance i

        ResultPage(String dataset, List<String> calcNames, List<String> distances, List<String[]> rows) {
            this.dataset = dataset;
            this.calcNames = calcNames;
            this.distances = distances;
            this.rows = rows;
        }
    }

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

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeThresholdPx = dp(48);

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

        // NEW: table views from the updated layout
        tripDetailTableHeaderGroup = tripDetailBubble.findViewById(R.id.trip_detail_table_header_group);
        tripDetailTableHeader = tripDetailBubble.findViewById(R.id.trip_detail_table_header);
        tripDetailTableRows = tripDetailBubble.findViewById(R.id.trip_detail_table_rows);

        tripDetailPrevBt = tripDetailBubble.findViewById(R.id.trip_detail_prev_bt);
        tripDetailNextBt = tripDetailBubble.findViewById(R.id.trip_detail_next_bt);
        tripDetailPageIndicatorTv = tripDetailBubble.findViewById(R.id.trip_detail_page_indicator_tv);

        tripDetailPrevBt.setOnClickListener(v -> showPreviousPage());
        tripDetailNextBt.setOnClickListener(v -> showNextPage());

        // The same listener goes on every surface the finger can land on, including the
        // table cells created later in showCurrentPage(), so swipes work over the whole bubble.
        swipeListener = (v, event) -> handleSwipeTouch(event);
        tripDetailBubble.setOnTouchListener(swipeListener);
        tripDetailScroll.setOnTouchListener(swipeListener);
        tripDetailDistanceTv.setOnTouchListener(swipeListener);
        tripDetailTableRows.setOnTouchListener(swipeListener);

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
            String dataset = datasetKeys.next();                 // "Green space"
            JSONObject calcs = results.optJSONObject(dataset);   // {"Area": {...}, "Count": {...}}
            if (calcs == null) continue;                         // skip non-object entries such as flags
            resultPages.add(parseDataset(dataset, calcs));
        }
    }

    /**
     * CHANGED (replaces renderDatasetBody): turns one dataset into table data.
     *
     * Columns are the calculation types (sorted by name so the order is stable, because
     * JSONObject.keys() has no guaranteed order). Rows are the union of distances over all
     * calculations, sorted ascending by numeric distance. A calculation with no value at a
     * given distance shows a dash instead of leaving a hole.
     */
    private ResultPage parseDataset(String dataset, JSONObject calcs) throws JSONException {
        List<String> calcNames = new ArrayList<>();
        Iterator<String> calcKeys = calcs.keys();
        while (calcKeys.hasNext()) {
            String calc = calcKeys.next();
            if (calc.equals("collapse") || calc.equals("display_order")) continue;
            if (calcs.optJSONObject(calc) != null) calcNames.add(calc);
        }
        calcNames.sort(String.CASE_INSENSITIVE_ORDER);

        Map<String, String[]> valuesByDistance = new HashMap<>();
        for (int c = 0; c < calcNames.size(); c++) {
            JSONObject values = calcs.getJSONObject(calcNames.get(c));
            Iterator<String> distanceKeys = values.keys();
            while (distanceKeys.hasNext()) {
                String distanceKey = distanceKeys.next();
                if (distanceKey.equals("collapse") || distanceKey.equals("display_order")) continue;

                String[] cells = valuesByDistance.get(distanceKey);
                if (cells == null) {
                    cells = new String[calcNames.size()];
                    Arrays.fill(cells, EMPTY_CELL);
                    valuesByDistance.put(distanceKey, cells);
                }
                cells[c] = cleanValue(extractDistanceValue(values, distanceKey));
            }
        }

        List<String> distances = new ArrayList<>(valuesByDistance.keySet());
        distances.sort(BottomSheetManager::compareDistanceKeys);   // ascending: 400 m, 800 m, 1.5 km

        List<String[]> rows = new ArrayList<>();
        for (String distance : distances) rows.add(valuesByDistance.get(distance));

        return new ResultPage(dataset, calcNames, distances, rows);
    }

    /**
     * Reads the value stored under one distance key, tolerating both shapes:
     *   "400 m": "1200 m²"                       (plain string)
     *   "400 m": { "<time range>": "1200 m²" }   (nested by time range)
     */
    private String extractDistanceValue(JSONObject values, String distanceKey) throws JSONException {
        Object raw = values.get(distanceKey);
        if (raw instanceof JSONObject) {
            return extractValue((JSONObject) raw);
        }
        return String.valueOf(raw);
    }

    private String extractValue(JSONObject byTimeRange) throws JSONException {
        Iterator<String> keys = byTimeRange.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.equals("collapse")) continue;
            return byTimeRange.getString(key);   // the time-range label itself, if you want it later, is `key`
        }
        return "";
    }

    /**
     * CHANGED: draws the current dataset as a table. The header (column titles) lives outside the
     * scroll view so it stays pinned; the rows scroll underneath it.
     */
    private void showCurrentPage() {
        boolean hasPages = !resultPages.isEmpty();
        tripDetailPrevBt.setEnabled(hasPages);
        tripDetailNextBt.setEnabled(hasPages);

        tripDetailTableHeader.removeAllViews();
        tripDetailTableRows.removeAllViews();
        tripDetailScroll.scrollTo(0, 0);   // each page starts at the top

        if (!hasPages) {
            tripDetailTableHeaderGroup.setVisibility(View.GONE);
            tripDetailDistanceTv.setVisibility(View.VISIBLE);
            tripDetailDistanceTv.setText("No exposure data");
            tripDetailPageIndicatorTv.setText("");
            return;
        }

        ResultPage page = resultPages.get(currentPageIndex);
        tripDetailDistanceTv.setVisibility(View.GONE);
        tripDetailTableHeaderGroup.setVisibility(View.VISIBLE);

        tripDetailTableHeader.addView(makeCell("Distance", true, false));
        for (String calc : page.calcNames) {
            tripDetailTableHeader.addView(makeCell(calc, true, true));
        }

        for (int r = 0; r < page.distances.size(); r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.setOnTouchListener(swipeListener);

            row.addView(makeCell(page.distances.get(r), false, false));
            for (String value : page.rows.get(r)) {
                row.addView(makeCell(value, false, true));
            }
            tripDetailTableRows.addView(row);
        }

        tripDetailPageIndicatorTv.setText(page.dataset);
    }

    /**
     * NEW: one table cell. Header and body cells use identical weights, which is what keeps the
     * pinned header aligned with the scrolling rows. The first column (distance) is left
     * aligned; the value columns are right aligned so numbers line up.
     */
    private TextView makeCell(String text, boolean header, boolean valueColumn) {
        TextView tv = new TextView(context);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, valueColumn ? 1.1f : 0.9f));
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, header ? 13 : 14);
        tv.setGravity(valueColumn ? Gravity.END : Gravity.START);
        if (header) {
            tv.setTypeface(null, Typeface.BOLD);
            tv.setAlpha(0.7f);   // softer than the data without hard-coding a colour (works in dark mode)
        }
        tv.setOnTouchListener(swipeListener);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void updateTripDetailBubble(TrajectorySegment clickedSegment) {
        if (clickedSegment != null) {
            tripDetailIdTv.setText("Loading…");

            // CHANGED: clear the previous trip's table while the new one loads
            tripDetailTableHeaderGroup.setVisibility(View.GONE);
            tripDetailTableHeader.removeAllViews();
            tripDetailTableRows.removeAllViews();
            tripDetailDistanceTv.setVisibility(View.VISIBLE);
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
        //remove number from stay, stay currently starts on 1 for whichever timeframe is queried
        if (key.startsWith("stay")) return "Stay";
        String withSpace = key.replace("-", " ");
        return Character.toUpperCase(withSpace.charAt(0)) + withSpace.substring(1);
    }

    private String cleanValue(String rawValue) {
        // Strips a trailing unit bracket like " [-]" or " [m]"
        return rawValue.replaceAll("\\s*\\[.*?]\\s*$", "").trim();
    }

    /**
     * Converts a distance key from the JSON into metres so keys can be compared numerically.
     * Sorting the raw strings would put "1000" before "200", so we parse instead.
     * Keys with no leading number sort to the end rather than crashing.
     */
    private static double distanceInMetres(String key) {
        Matcher m = DISTANCE_KEY_PATTERN.matcher(key);
        if (!m.find()) return Double.MAX_VALUE;
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        return (unit != null && unit.equalsIgnoreCase("km")) ? value * 1000 : value;
    }

    // Ascending by distance; ties (or unparseable keys) fall back to plain string order
    // so the result is always deterministic.
    private static int compareDistanceKeys(String a, String b) {
        int byDistance = Double.compare(distanceInMetres(a), distanceInMetres(b));
        return byDistance != 0 ? byDistance : a.compareTo(b);
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

    /**
     * Raw (screen) coordinates are used throughout, so it doesn't matter which view receives the
     * event or how far the text has scrolled. Vertical drags scroll the table by hand; clearly
     * horizontal swipes change the dataset.
     */
    private boolean handleSwipeTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartX = event.getRawX();
                swipeStartY = event.getRawY();
                lastTouchY = event.getRawY();
                axisLock = AXIS_NONE;
                scrolledVertically = false;
                tripDetailScroll.requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - swipeStartX;
                float dy = event.getRawY() - swipeStartY;

                if (axisLock == AXIS_NONE
                        && (Math.abs(dx) > touchSlop * 2 || Math.abs(dy) > touchSlop * 2)) {
                    axisLock = Math.abs(dy) > Math.abs(dx) * VERTICAL_BIAS
                            ? AXIS_VERTICAL : AXIS_HORIZONTAL;
                }

                if (axisLock == AXIS_VERTICAL) {
                    int before = tripDetailScroll.getScrollY();
                    tripDetailScroll.scrollBy(0, Math.round(lastTouchY - event.getRawY()));
                    if (tripDetailScroll.getScrollY() != before) scrolledVertically = true;
                }
                lastTouchY = event.getRawY();
                return true;

            case MotionEvent.ACTION_UP:
                float diffX = event.getRawX() - swipeStartX;
                float diffY = event.getRawY() - swipeStartY;

                boolean farEnough = Math.abs(diffX) > swipeThresholdPx;
                boolean isSwipe = farEnough && (axisLock == AXIS_HORIZONTAL
                        || (!scrolledVertically && Math.abs(diffX) > Math.abs(diffY)));

                if (isSwipe) {
                    if (diffX < 0) showNextPage(); else showPreviousPage();
                }
                axisLock = AXIS_NONE;
                tripDetailScroll.requestDisallowInterceptTouchEvent(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                axisLock = AXIS_NONE;
                tripDetailScroll.requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return false;
    }
}