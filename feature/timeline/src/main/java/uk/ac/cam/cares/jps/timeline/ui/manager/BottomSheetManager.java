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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
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
 * A UI manager that manages bottom sheets on screen and switches between them based on state.
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

    // Floating map controls that fade as the sheet expands
    private final View legendCard;
    private final View recordingFab;

    // Trip detail bubble
    private final View tripDetailBubble;
    private final TextView tripDetailEyebrowTv;
    private final TextView tripDetailIdTv;
    private final TextView tripDetailDistanceTv;
    private final View tripDetailTableHeaderGroup;
    private final LinearLayout tripDetailTableHeader;
    private final LinearLayout tripDetailTableRows;
    private final NestedScrollView tripDetailScroll;
    private final ImageButton tripDetailPrevBt;
    private final ImageButton tripDetailNextBt;
    private final TextView tripDetailPageIndicatorTv;

    private final List<ResultPage> resultPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private TrajectorySegment pendingSegment;

    // Swipe / scroll handling
    private static final int AXIS_NONE = 0, AXIS_HORIZONTAL = 1, AXIS_VERTICAL = 2;
    private static final float VERTICAL_BIAS = 1.5f;
    private int axisLock = AXIS_NONE;
    private float swipeStartX = 0f;
    private float swipeStartY = 0f;
    private float lastTouchY = 0f;
    private boolean scrolledVertically = false;
    private final int touchSlop;
    private final int swipeThresholdPx;
    private final View.OnTouchListener swipeListener;

    private static final Pattern DISTANCE_KEY_PATTERN =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(km|m)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRICT_DISTANCE_KEY =
            Pattern.compile("^\\s*\\d+(?:\\.\\d+)?\\s*(?:km|m)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final String EMPTY_CELL = "–";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final class ResultPage {
        final String dataset;
        final List<String> calcNames;
        final List<String> distances;
        final List<String[]> rows;

        ResultPage(String dataset, List<String> calcNames, List<String> distances, List<String[]> rows) {
            this.dataset = dataset;
            this.calcNames = calcNames;
            this.distances = distances;
            this.rows = rows;
        }
    }

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
        legendCard = rootView.findViewById(R.id.legend_card);
        recordingFab = rootView.findViewById(R.id.recording_fab);

        tripDetailBubble = rootView.findViewById(R.id.trip_detail_bubble);
        tripDetailEyebrowTv = rootView.findViewById(R.id.trip_detail_eyebrow_tv);
        tripDetailIdTv = rootView.findViewById(R.id.trip_detail_id_tv);
        tripDetailDistanceTv = rootView.findViewById(R.id.trip_detail_distance_tv);
        tripDetailBubble.findViewById(R.id.trip_detail_close_bt)
                .setOnClickListener(v -> trajectoryViewModel.removeAllClicked());
        tripDetailScroll = tripDetailBubble.findViewById(R.id.trip_detail_scroll);

        tripDetailTableHeaderGroup = tripDetailBubble.findViewById(R.id.trip_detail_table_header_group);
        tripDetailTableHeader = tripDetailBubble.findViewById(R.id.trip_detail_table_header);
        tripDetailTableRows = tripDetailBubble.findViewById(R.id.trip_detail_table_rows);
        tripDetailPrevBt = tripDetailBubble.findViewById(R.id.trip_detail_prev_bt);
        tripDetailNextBt = tripDetailBubble.findViewById(R.id.trip_detail_next_bt);
        tripDetailPageIndicatorTv = tripDetailBubble.findViewById(R.id.trip_detail_page_indicator_tv);

        tripDetailPrevBt.setOnClickListener(v -> showPreviousPage());
        tripDetailNextBt.setOnClickListener(v -> showNextPage());

        swipeListener = (v, event) -> handleSwipeTouch(event);
        tripDetailBubble.setOnTouchListener(swipeListener);
        tripDetailScroll.setOnTouchListener(swipeListener);
        tripDetailDistanceTv.setOnTouchListener(swipeListener);
        tripDetailTableRows.setOnTouchListener(swipeListener);

        setupBottomSheetBehavior();
        initBottomSheet();
    }

    private void setupBottomSheetBehavior() {
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    setFloatingControlsAlpha(0f);
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED
                        || newState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                    setFloatingControlsAlpha(1f);
                }
                updateSessionsBottomPadding();
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                float halfRatio = bottomSheetBehavior.getHalfExpandedRatio();
                float fade = 1f - Math.max(0f, Math.min(1f, (slideOffset - halfRatio) / 0.25f));
                setFloatingControlsAlpha(fade);
                updateSessionsBottomPadding();
            }
        });
    }

    private void setFloatingControlsAlpha(float alpha) {
        int visibility = alpha <= 0.02f ? View.INVISIBLE : View.VISIBLE;
        if (legendCard != null) {
            legendCard.setAlpha(alpha);
            legendCard.setVisibility(visibility);
        }
        if (recordingFab != null) {
            recordingFab.setAlpha(alpha);
            recordingFab.setVisibility(visibility);
        }
    }

    private void updateSessionsBottomPadding() {
        if (normalBottomSheet == null || normalBottomSheet.getBottomSheet() == null) return;
        RecyclerView sessionsRv = normalBottomSheet.getBottomSheet().findViewById(R.id.sessions_recycler_view);
        if (sessionsRv == null) return;
        int desiredBottom = Math.max(0, bottomSheetContainer.getTop()) + dp(24);
        if (sessionsRv.getPaddingBottom() != desiredBottom) {
            sessionsRv.setPadding(
                    sessionsRv.getPaddingLeft(),
                    sessionsRv.getPaddingTop(),
                    sessionsRv.getPaddingRight(),
                    desiredBottom
            );
        }
    }

    private void configureDragHandle(BottomSheet sheet) {
        View handle = sheet.getBottomSheet().findViewById(R.id.drag_handle);
        if (handle == null) return;
        handle.setOnClickListener(v -> {
            int state = bottomSheetBehavior.getState();
            if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else if (state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            } else {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });
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
        configureDragHandle(normalBottomSheet);
        configureDateSelection();
        configureTrajectoryRetrieval();
        configureSummary();
        configureExposureFeatureInfo();
    }

    private void configureTrajectoryRetrieval() {
        trajectoryViewModel.isFetchingTrajectory.observe(lifecycleOwner, isFetching -> {
            normalBottomSheet.showFetchingAnimation(isFetching);
            bottomSheetContainer.post(this::updateSessionsBottomPadding);
        });
    }

    private void configureSummary() {
        trajectoryViewModel.trajectory.observe(lifecycleOwner, trajectoryByDate -> {
            List<ActivitySummary> activityItemSummaryList = trajectoryByDate.getActivitySummary();
            List<Session> uniqueSessions = trajectoryByDate.getSessions();
            if (trajectoryByDate.getDate().equals(normalBottomSheetViewModel.selectedDate.getValue())) {
                normalBottomSheet.updateSummaryView(activityItemSummaryList);
                normalBottomSheet.updateSessionsList(uniqueSessions, null);
                bottomSheetContainer.post(this::updateSessionsBottomPadding);
            }
        });

        trajectoryViewModel.clickedSegment.observe(lifecycleOwner, clickedSegment -> {
            normalBottomSheet.highlightClickedSegment(clickedSegment);
            if (clickedSegment != null && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
            }
            updateTripDetailBubble(clickedSegment);
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
                    tripDetailIdTv.setText(fallbackSegmentTitle(pendingSegment));
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
            String dataset = datasetKeys.next();
            JSONObject calcs = results.optJSONObject(dataset);
            if (calcs == null) continue;
            resultPages.add(parseDataset(dataset, calcs));
        }
    }

    private static boolean isControlKey(String key) {
        return key.equals("collapse") || key.equals("display_order");
    }

    private static final class Leaf {
        final List<String> path;
        final String value;

        Leaf(List<String> path, String value) {
            this.path = path;
            this.value = value;
        }
    }

    private void collectLeaves(JSONObject node, List<String> path, List<Leaf> out) {
        Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isControlKey(key)) continue;
            Object child = node.opt(key);
            if (child == null || child == JSONObject.NULL || child instanceof JSONArray) continue;

            List<String> next = new ArrayList<>(path);
            next.add(key);
            if (child instanceof JSONObject) {
                collectLeaves((JSONObject) child, next, out);
            } else {
                out.add(new Leaf(next, String.valueOf(child)));
            }
        }
    }

    private ResultPage parseDataset(String dataset, JSONObject root) throws JSONException {
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(root, new ArrayList<>(), leaves);

        List<String[]> entries = new ArrayList<>();
        for (Leaf leaf : leaves) {
            int distanceIdx = -1;
            for (int i = 0; i < leaf.path.size(); i++) {
                String key = leaf.path.get(i);
                if (key.equals("-") || STRICT_DISTANCE_KEY.matcher(key).matches()) {
                    distanceIdx = i;
                    break;
                }
            }
            String column;
            String row;
            if (distanceIdx < 0) {
                column = String.join("\n", leaf.path);
                row = "-";
            } else {
                column = String.join("\n", leaf.path.subList(0, distanceIdx));
                row = leaf.path.get(distanceIdx);
            }
            if (column.isEmpty()) column = dataset;
            entries.add(new String[]{column, row, cleanValue(leaf.value)});
        }

        List<String> calcNames = new ArrayList<>();
        for (String[] e : entries) if (!calcNames.contains(e[0])) calcNames.add(e[0]);
        calcNames.sort(String.CASE_INSENSITIVE_ORDER);

        Map<String, String[]> valuesByDistance = new HashMap<>();
        for (String[] e : entries) {
            String[] cells = valuesByDistance.get(e[1]);
            if (cells == null) {
                cells = new String[calcNames.size()];
                Arrays.fill(cells, EMPTY_CELL);
                valuesByDistance.put(e[1], cells);
            }
            int col = calcNames.indexOf(e[0]);
            if (cells[col].equals(EMPTY_CELL)) cells[col] = e[2];
        }

        List<String> distances = new ArrayList<>(valuesByDistance.keySet());
        distances.sort(BottomSheetManager::compareDistanceKeys);
        List<String[]> rows = new ArrayList<>();
        for (String distance : distances) rows.add(valuesByDistance.get(distance));
        return new ResultPage(dataset, calcNames, distances, rows);
    }

    private void showCurrentPage() {
        boolean hasPages = !resultPages.isEmpty();
        boolean canSwitch = resultPages.size() > 1;
        tripDetailPrevBt.setEnabled(canSwitch);
        tripDetailNextBt.setEnabled(canSwitch);
        tripDetailPrevBt.setAlpha(canSwitch ? 1.0f : 0.35f);
        tripDetailNextBt.setAlpha(canSwitch ? 1.0f : 0.35f);

        tripDetailTableHeader.removeAllViews();
        tripDetailTableRows.removeAllViews();
        tripDetailScroll.scrollTo(0, 0);

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

        tripDetailTableHeader.addView(makeCell("Buffer", true, false));
        for (String calc : page.calcNames) {
            tripDetailTableHeader.addView(makeCell(calc, true, true));
        }

        for (int r = 0; r < page.distances.size(); r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(7), 0, dp(7));
            row.setOnTouchListener(swipeListener);

            row.addView(makeCell(page.distances.get(r), false, false));
            for (String value : page.rows.get(r)) {
                row.addView(makeCell(value, false, true));
            }
            tripDetailTableRows.addView(row);
        }

        tripDetailPageIndicatorTv.setText(page.dataset + " · " + (currentPageIndex + 1) + " / " + resultPages.size());
    }

    private TextView makeCell(String text, boolean header, boolean valueColumn) {
        TextView tv = new TextView(context);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, valueColumn ? 1.1f : 0.9f));
        tv.setText(text);
        tv.setFontFeatureSettings("tnum");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, header ? 11 : 13);
        tv.setGravity(valueColumn ? Gravity.END : Gravity.START);
        if (header) {
            tv.setTypeface(null, Typeface.BOLD);
            tv.setAlpha(0.75f);
        } else if (!valueColumn) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        tv.setOnTouchListener(swipeListener);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void updateTripDetailBubble(TrajectorySegment clickedSegment) {
        if (clickedSegment != null) {
            if (tripDetailEyebrowTv != null) {
                String range = formatTimeRange(clickedSegment.getStartTime(), clickedSegment.getEndTime());
                String base = context.getString(R.string.exposure_summary_eyebrow);
                tripDetailEyebrowTv.setText(range.isEmpty() ? base : base + " · " + range);
            }
            tripDetailIdTv.setText(fallbackSegmentTitle(clickedSegment));
            tripDetailTableHeaderGroup.setVisibility(View.GONE);
            tripDetailTableHeader.removeAllViews();
            tripDetailTableRows.removeAllViews();
            tripDetailDistanceTv.setVisibility(View.VISIBLE);
            tripDetailDistanceTv.setText("Loading exposure summary…");
            tripDetailPageIndicatorTv.setText("");
            tripDetailPrevBt.setEnabled(false);
            tripDetailNextBt.setEnabled(false);
            tripDetailPrevBt.setAlpha(0.35f);
            tripDetailNextBt.setAlpha(0.35f);
            tripDetailBubble.setVisibility(View.VISIBLE);

            pendingSegment = clickedSegment;
            exposureFeatureInfoViewModel.getTimelineResults(clickedSegment.getStartTime(), clickedSegment.getEndTime());
        } else {
            tripDetailBubble.setVisibility(View.GONE);
        }
    }

    private String fallbackSegmentTitle(TrajectorySegment segment) {
        if (segment == null) return "";
        return segment.getTrip() == 0 ? "Stay" : "Trip " + segment.getTrip();
    }

    private String formatTimeRange(long startMs, long endMs) {
        if (startMs <= 0 || endMs <= 0) return "";
        ZoneId zone = ZoneId.systemDefault();
        String start = Instant.ofEpochMilli(startMs).atZone(zone).format(TIME_FMT);
        String end = Instant.ofEpochMilli(endMs).atZone(zone).format(TIME_FMT);
        return start + " – " + end;
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

        normalBottomSheet.getBottomSheet().findViewById(R.id.date_picker_layout).setOnClickListener(view -> {
            if (normalBottomSheetViewModel.datesWithTrajectory.getValue() == null) {
                normalBottomSheetViewModel.getDatesWithTrajectory(currentUtcOffsetId());
            }
            getDatePicker(normalBottomSheetViewModel).show(fragmentManager, "date_picker");
        });

        normalBottomSheetViewModel.selectedDate.observe(lifecycleOwner, selectedDate -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("E, MMMM dd, yyyy");
            ((TextView) normalBottomSheet.getBottomSheet().findViewById(R.id.date_tv)).setText(selectedDate.format(formatter));
            connectionViewModel.checkNetworkConnection();
        });

        normalBottomSheetViewModel.datesWithTrajectory.observe(lifecycleOwner, dates -> greyOutDecorator.setDatesWithTrajectory(dates));

        normalBottomSheetViewModel.datesError.observe(lifecycleOwner, error -> {
            if (error == null) return;
            LOGGER.error("Could not load dates with data", error);
            Toast.makeText(context, "Couldn't load dates with data. Will retry next time.", Toast.LENGTH_LONG).show();
            normalBottomSheetViewModel.clearDatesError();
        });

        normalBottomSheetViewModel.getDatesWithTrajectory(currentUtcOffsetId());
    }

    private String currentUtcOffsetId() {
        return ZoneId.systemDefault().getId();
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
        configureDragHandle(errorBottomSheet);
        userPhoneViewModel.getError().observe(lifecycleOwner, error -> {
            if (error == null) {
                return;
            }
            if (error instanceof AccountException) {
                sessionExpiredDialog.show();
            } else if (error instanceof SensorCollectionStateException) {
                errorBottomSheet.setErrorType(ErrorBottomSheet.ErrorType.ACCOUNT_ERROR);
            }
        });

        trajectoryViewModel.trajectoryError.observe(lifecycleOwner, error -> {
            if (error == null) {
                return;
            }
            if (error instanceof AccountException) {
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
        bottomSheetContainer.post(this::updateSessionsBottomPadding);
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
        if (key.startsWith("stay")) return "Stay";
        String withSpace = key.replace("-", " ");
        return Character.toUpperCase(withSpace.charAt(0)) + withSpace.substring(1);
    }

    private String cleanValue(String rawValue) {
        return rawValue.replaceAll("\\s*\\[.*?]\\s*$", "").trim();
    }

    private static double distanceInMetres(String key) {
        Matcher m = DISTANCE_KEY_PATTERN.matcher(key);
        if (!m.find()) return Double.MAX_VALUE;
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        return (unit != null && unit.equalsIgnoreCase("km")) ? value * 1000 : value;
    }

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