package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;


import com.mapbox.bindgen.Expected;
import com.mapbox.bindgen.None;
import com.mapbox.bindgen.Value;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.LayerPosition;
import com.mapbox.maps.MapView;
import com.mapbox.maps.QueriedFeature;
import com.mapbox.maps.RenderedQueryGeometry;
import com.mapbox.maps.RenderedQueryOptions;
import com.mapbox.maps.ScreenBox;
import com.mapbox.maps.ScreenCoordinate;
import com.mapbox.maps.Style;
import com.mapbox.maps.plugin.animation.MapAnimationOptions;
import com.mapbox.maps.plugin.gestures.GesturesPlugin;
import com.mapbox.maps.plugin.gestures.GesturesUtils;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import uk.ac.cam.cares.jps.timeline.model.trajectory.ActivityTrajectory;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectoryByDate;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;
import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.TrajectoryViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.RoutesViewModel;
import uk.ac.cam.cares.jps.timelinemap.R;

import uk.ac.cam.cares.jps.timeline.viewmodel.TripAgentViewModel;
import java.time.LocalDate;

/**
 * An UI manager that manages the drawing and removing of trajectories received from server
 */
public class TrajectoryManager {
    private final TrajectoryViewModel trajectoryViewModel;

    private final NormalBottomSheetViewModel normalBottomSheetViewModel;
    private final Logger LOGGER = Logger.getLogger(TrajectoryManager.class);
    private final List<String> layerNames = new ArrayList<>();
    private final Map<String, String> activityColors = new HashMap<>();
    private String layerId;
    private JSONArray baseColorExpression;
    private static final String GREYED_OUT_COLOR = "#BDBDBD";
    private static final double SAME_SESSION_OPACITY = 0.35;
    private static final double OTHER_SESSION_OPACITY = 0.3;

    private enum ColorMode { TRIP, ACTIVITY }
    private ColorMode colorMode = ColorMode.TRIP;
    private TrajectoryByDate currentTrajectory;
    private ActivityTrajectory activityTrajectory;   // activity-split data for the current date
    private LocalDate activityFetchDate;             // date a fetch was already requested for
    private View legendRoot;
    private Button modeButton;
    private final RoutesViewModel routesViewModel;   // to know whether we are in route mode
    private final MapView mapView;                   // needed by setTrajectoryVisible() outside the constructor
    private boolean trajectoryVisible = true;
    private final TripAgentViewModel tripAgentViewModel;


    /**
     * Constructor of the class
     *
     * @param fragment Host fragment
     * @param mapView  Mapbox map view
     */
    /*
    public TrajectoryManager(Fragment fragment, MapView mapView) {
        trajectoryViewModel = new ViewModelProvider(fragment).get(TrajectoryViewModel.class);
        normalBottomSheetViewModel = new ViewModelProvider(fragment).get(NormalBottomSheetViewModel.class);

        activityColors.put("walking", getColorHex(fragment.requireContext(), com.google.android.material.R.attr.colorPrimary));
        activityColors.put("still", getColorHex(fragment.requireContext(), R.attr.colorQuaternary));
        activityColors.put("vehicle", getColorHex(fragment.requireContext(), com.google.android.material.R.attr.colorSecondary));
        activityColors.put("bike", getColorHex(fragment.requireContext(), com.google.android.material.R.attr.colorTertiary));
        activityColors.put("default", getColorHex(fragment.requireContext(), R.attr.colorDefault));

        String selectedColor = getColorHex(fragment.requireContext(), R.attr.colorSelected);

        trajectoryViewModel.trajectory.observe(fragment.getViewLifecycleOwner(), trajectoryByDate -> {
            trajectoryViewModel.removeAllClicked();
            if (!trajectoryByDate.getDate().equals(normalBottomSheetViewModel.selectedDate.getValue())) {
                // trajectoryViewModel.setClicked(null);
                trajectoryViewModel.setFetching(true);
                return;
            }

            mapView.getMapboxMap().getStyle(style -> {
                // trajectoryViewModel.setClicked(null);
                removeAllLayers(style);
                if (!trajectoryByDate.getTrajectoryStr().isEmpty()) {
                    paintTrajectoryByActivity(style, trajectoryByDate, activityColors, "default");
                    addTrajectoryClickListener(mapView, selectedColor, fragment.getViewLifecycleOwner());
                }
            });

            resetCameraCentre(mapView, trajectoryByDate);
        });
    }
    */
    // was: activityColors keyed by "walking"/"still"/"vehicle"/"bike"/"default"
    private final Map<String, String> tripColors = new HashMap<>();

    public TrajectoryManager(Fragment fragment, MapView mapView) {
        trajectoryViewModel = new ViewModelProvider(fragment).get(TrajectoryViewModel.class);
        normalBottomSheetViewModel = new ViewModelProvider(fragment).get(NormalBottomSheetViewModel.class);
        tripAgentViewModel = new ViewModelProvider(fragment).get(TripAgentViewModel.class);
        routesViewModel = new ViewModelProvider(fragment.requireActivity()).get(RoutesViewModel.class);
        this.mapView = mapView;

        // colours used when the trajectory is coloured by activity
        activityColors.put("walking", getColorHex(fragment.requireContext(), com.google.android.material.R.attr.colorPrimary));
        activityColors.put("still", getColorHex(fragment.requireContext(), R.attr.colorQuaternary));
        activityColors.put("vehicle", getColorHex(fragment.requireContext(), com.google.android.material.R.attr.colorSecondary));
        activityColors.put("bike", getColorHex(fragment.requireContext(), com.google.android.material.R.attr.colorTertiary));
        activityColors.put("default", getColorHex(fragment.requireContext(), R.attr.colorDefault));

        // trip == 0 (stationary/stay segment) gets one color
        tripColors.put("0", "#FF0000");
        // any other trip number gets the other color
        tripColors.put("default", "#008000");

        String selectedColor = getColorHex(fragment.requireContext(), R.attr.colorSelected);

        // TrajectoryManager's click listener — back to just passing the date
        fragment.requireView().findViewById(R.id.run_trip_agent_button)
                .setOnClickListener(v -> tripAgentViewModel.runTripAgent(normalBottomSheetViewModel.selectedDate.getValue()));

        // toggle between colouring by trips/visits and colouring by activity
        legendRoot = fragment.requireView();
        modeButton = fragment.requireView().findViewById(R.id.trajectory_mode_button);
        updateModeUi();
        // activity-split data arrives separately, only when Activity mode needs it
        trajectoryViewModel.activityTrajectory.observe(fragment.getViewLifecycleOwner(), data -> {
            if (data == null || currentTrajectory == null || !data.getDate().equals(currentTrajectory.getDate())) {
                return;
            }
            activityTrajectory = data;
            if (colorMode == ColorMode.ACTIVITY) {
                mapView.getMapboxMap().getStyle(this::paintCurrentTrajectory);
            }
        });
        trajectoryViewModel.activityTrajectoryError.observe(fragment.getViewLifecycleOwner(), error -> {
            if (error == null) {
                return;
            }
            activityFetchDate = null;
            if (colorMode == ColorMode.ACTIVITY) {
                colorMode = ColorMode.TRIP;
                updateModeUi();
                Toast.makeText(mapView.getContext(), "Could not load activity data", Toast.LENGTH_SHORT).show();
                mapView.getMapboxMap().getStyle(this::paintCurrentTrajectory);
            }
        });

        modeButton.setOnClickListener(v -> {
            colorMode = colorMode == ColorMode.TRIP ? ColorMode.ACTIVITY : ColorMode.TRIP;
            updateModeUi();
            trajectoryViewModel.removeAllClicked();   // clears highlight and fading, which belong to the old paint
            if (currentTrajectory != null) {
                mapView.getMapboxMap().getStyle(this::paintCurrentTrajectory);
            }
        });

        trajectoryViewModel.trajectory.observe(fragment.getViewLifecycleOwner(), trajectoryByDate -> {
            trajectoryViewModel.removeAllClicked();
            if (!trajectoryByDate.getDate().equals(normalBottomSheetViewModel.selectedDate.getValue())) {
                trajectoryViewModel.setFetching(true);
                return;
            }
            currentTrajectory = trajectoryByDate;
            activityTrajectory = null;
            activityFetchDate = null;

            mapView.getMapboxMap().getStyle(style -> {
                paintCurrentTrajectory(style);
                if (!trajectoryByDate.getTrajectoryStr().isEmpty()) {
                    addTrajectoryClickListener(mapView, selectedColor, fragment.getViewLifecycleOwner());
                }
            });

            resetCameraCentre(mapView, trajectoryByDate);
        });
    }

    public void setTrajectoryVisible(boolean visible) {
        trajectoryVisible = visible;
        mapView.getMapboxMap().getStyle(this::applyTrajectoryVisibility);
    }

    private void applyTrajectoryVisibility(Style style) {
        String visibility = trajectoryVisible ? "visible" : "none";
        if (layerId != null && style.styleLayerExists(layerId)) {
            style.setStyleLayerProperty(layerId, "visibility", Value.valueOf(visibility));
        }
        // The yellow highlight of a clicked segment is a separate layer, hide it too.
        if (style.styleLayerExists("highlight_layer")) {
            style.setStyleLayerProperty("highlight_layer", "visibility", Value.valueOf(visibility));
        }
    }

    private String getColorHex(Context context, int colorAttr) {
        TypedArray typedArray = context.getTheme().obtainStyledAttributes(new int[]{colorAttr});
        int color = typedArray.getColor(0, Color.BLACK);
        typedArray.recycle();
        return String.format("#%06X", (0xFFFFFF & color));
    }

    /**
     * Adds a click listener to detect which trajectory segment was clicked.
     */
    private void addTrajectoryClickListener(MapView mapView, String selectedColor, LifecycleOwner lifecycleOwner) {
        GesturesPlugin gesturesPlugin = GesturesUtils.getGestures(mapView);

        gesturesPlugin.addOnMapClickListener(point -> {

            if (routesViewModel.isRouteMode()) {
                return false;
            }
            // The activity data has different segment ids from the trips data, so segment
            // selection is only available in the trips view.
            if (colorMode == ColorMode.ACTIVITY) {
                return false;
            }
            Log.d("TrajectoryClick", "Map clicked at: " + point.longitude() + ", " + point.latitude());

            ScreenCoordinate screenPoint = mapView.getMapboxMap().pixelForCoordinate(point);
            ScreenBox screenBox = new ScreenBox(
                    new ScreenCoordinate(screenPoint.getX() - 20, screenPoint.getY() - 20),
                    new ScreenCoordinate(screenPoint.getX() + 20, screenPoint.getY() + 20)
            );

            mapView.getMapboxMap().queryRenderedFeatures(
                    new RenderedQueryGeometry(screenBox),
                    new RenderedQueryOptions(List.of(this.layerId), null),
                    result -> {
                        if (result.getValue() != null && !result.getValue().isEmpty()) {
                            QueriedFeature clickedFeature = result.getValue().get(0).getQueriedFeature();
                            Feature feature = clickedFeature.getFeature();

                            Integer segmentId = feature.hasProperty("id") ? feature.getNumberProperty("id").intValue() : null;
                            String sessionId = feature.hasProperty("session_id") ? feature.getStringProperty("session_id") : null;

                            if (segmentId != null && sessionId != null) {
                                trajectoryViewModel.setClickedSegment(segmentId, sessionId);
                            } else {
                                trajectoryViewModel.setClickedSegment(null, null);
                            }
                        } else {
                            trajectoryViewModel.setClickedSegment(null, null);
                        }
                    }
            );

            return true;
        });

        trajectoryViewModel.clickedSegment.observe(lifecycleOwner, clickedSegment -> {
            removeHighlightLayer(mapView.getMapboxMap().getStyle());
            updateSessionGreyOut(mapView.getMapboxMap().getStyle(), clickedSegment);
            if (clickedSegment != null) {
                highlightTrajectorySegment(mapView.getMapboxMap().getStyle(), clickedSegment, selectedColor);
                resetCameraCentre(mapView, clickedSegment.getBbox());
            }
        });
    }

    /**
     * Logs which activity_type values the trajectory data contains. Segments whose value is not
     * one of walking/still/vehicle/bike are drawn in the default grey.
     */
    private void logActivityTypes(String trajectory) {
        try {
            JSONArray features = new JSONObject(trajectory).getJSONArray("features");
            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < features.length(); i++) {
                JSONObject props = features.getJSONObject(i).optJSONObject("properties");
                String type = props == null ? "<no properties>" : props.optString("activity_type", "<missing>");
                counts.merge(type, 1, Integer::sum);
            }
            LOGGER.info("Activity colouring: activity_type values in data = " + counts);
        } catch (JSONException e) {
            LOGGER.warn("Could not read activity types from trajectory data", e);
        }
    }

    /** Repaints the current day's trajectory in the selected colour mode. */
    private void paintCurrentTrajectory(Style style) {
        removeAllLayers(style);
        if (currentTrajectory == null || currentTrajectory.getTrajectoryStr().isEmpty()) {
            return;
        }
        if (colorMode == ColorMode.ACTIVITY) {
            // The trips layer has no activity_type, so activity colouring uses its own data.
            LocalDate date = currentTrajectory.getDate();
            if (activityTrajectory != null && date.equals(activityTrajectory.getDate())
                    && !activityTrajectory.getGeojson().isEmpty()) {
                paintTrajectoryByActivity(style, activityTrajectory.getGeojson(), activityColors, "default");
            } else {
                // not loaded yet (or no activity data): keep showing the trips colouring
                paintTrajectoryByUserId(style, currentTrajectory, tripColors, "default");
                if (activityTrajectory == null && !date.equals(activityFetchDate)) {
                    activityFetchDate = date;
                    trajectoryViewModel.getActivityTrajectory(date);
                }
            }
        } else {
            paintTrajectoryByUserId(style, currentTrajectory, tripColors, "default");
        }
        applyTrajectoryVisibility(style);
    }

    /** Updates the toggle button text and the legend to match the colour mode. */
    private void updateModeUi() {
        boolean activity = colorMode == ColorMode.ACTIVITY;
        modeButton.setText(activity ? R.string.trajectory_mode_activity : R.string.trajectory_mode_trips);

        String[] labels = activity
                ? new String[]{"Walking", "Still", "Vehicle", "Bike"}
                : new String[]{"Visit", "Trip", "", ""};
        String[] colors = activity
                ? new String[]{activityColors.get("walking"), activityColors.get("still"),
                activityColors.get("vehicle"), activityColors.get("bike")}
                : new String[]{tripColors.get("0"), tripColors.get("default"), null, null};
        // the card grows when it shows two lines, lift it a little so it stays clear of the sheet
        View legendCard = legendRoot.findViewById(R.id.legend_card);
        if (legendCard != null) {
            float density = legendRoot.getResources().getDisplayMetrics().density;
            legendCard.setTranslationY((activity ? -122 : -108) * density);
        }
        int[] rows = {R.id.legend_row_1, R.id.legend_row_2, R.id.legend_row_3, R.id.legend_row_4};
        int[] swatches = {R.id.legend_swatch_1, R.id.legend_swatch_2, R.id.legend_swatch_3, R.id.legend_swatch_4};
        int[] texts = {R.id.legend_text_1, R.id.legend_text_2, R.id.legend_text_3, R.id.legend_text_4};
        for (int i = 0; i < rows.length; i++) {
            View row = legendRoot.findViewById(rows[i]);
            if (colors[i] == null) {
                row.setVisibility(View.GONE);
                continue;
            }
            row.setVisibility(View.VISIBLE);
            legendRoot.findViewById(swatches[i]).setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor(colors[i])));
            ((TextView) legendRoot.findViewById(texts[i])).setText(labels[i]);
        }
    }

    /**
     * Fades every segment except the selected one: other segments of the same session keep
     * their colour but fade; segments of other sessions turn grey and fade.
     * The selected segment is drawn on top. Passing null restores the normal look.
     */
    private void updateSessionGreyOut(Style style, TrajectorySegment clickedSegment) {
        if (style == null || layerId == null || baseColorExpression == null
                || !style.styleLayerExists(layerId)) {
            return;
        }
        try {
            Object colorValue = baseColorExpression;
            Object opacityValue = 1.0;
            Object sortKeyValue = 0;
            if (clickedSegment != null && clickedSegment.getSessionId() != null) {
                JSONArray sameSession = new JSONArray().put("==")
                        .put(new JSONArray().put("get").put("session_id"))
                        .put(clickedSegment.getSessionId());
                JSONArray selected = new JSONArray().put("all")
                        .put(sameSession)
                        .put(new JSONArray().put("==")
                                .put(new JSONArray().put("get").put("id"))
                                .put(clickedSegment.getId()));
                colorValue = new JSONArray().put("case").put(sameSession)
                        .put(baseColorExpression).put(GREYED_OUT_COLOR);
                opacityValue = new JSONArray().put("case")
                        .put(selected).put(1.0)
                        .put(sameSession).put(SAME_SESSION_OPACITY)
                        .put(OTHER_SESSION_OPACITY);
                sortKeyValue = new JSONArray().put("case")
                        .put(selected).put(2)
                        .put(sameSession).put(1)
                        .put(0);
            }
            setLayerProperty(style, "line-color", colorValue);
            setLayerProperty(style, "line-opacity", opacityValue);
            setLayerProperty(style, "line-sort-key", sortKeyValue);
        } catch (Exception e) {
            LOGGER.error("Error fading segments", e);
        }
    }

    private void setLayerProperty(Style style, String property, Object value) {
        Expected<String, None> result = style.setStyleLayerProperty(
                layerId, property,
                Objects.requireNonNull(Value.fromJson(String.valueOf(value)).getValue()));
        if (result.isError()) {
            LOGGER.error("Failed to set " + property + ": " + result.getError());
        }
    }

    private void removeHighlightLayer(Style style) {
        if (style != null && style.styleLayerExists("highlight_layer")) {
            style.removeStyleLayer("highlight_layer");
        }
    }

    private void highlightTrajectorySegment(Style style, TrajectorySegment clickedSegment, String selectedColor) {
        removeHighlightLayer(style); // Ensure no duplicate highlights

        try {
            JSONObject paint = new JSONObject();
            paint.put("line-color", selectedColor);
            paint.put("line-width", 8);

            JSONArray filter = new JSONArray();
            filter.put("all");

            JSONArray idFilter = new JSONArray();
            idFilter.put("==");
            idFilter.put("id");
            idFilter.put(clickedSegment.getId());

            JSONArray sessionFilter = new JSONArray();
            sessionFilter.put("==");
            sessionFilter.put("trip");
            sessionFilter.put(clickedSegment.getSessionId());

            filter.put(idFilter);
            filter.put(sessionFilter);

            JSONObject layerJson = new JSONObject();
            layerJson.put("id", "highlight_layer");
            layerJson.put("type", "line");
            layerJson.put("source", "trajectory_default");
            layerJson.put("filter", filter);
            layerJson.put("paint", paint);

            style.addStyleLayer(
                    Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                    new LayerPosition(null, null, null)
            );
        } catch (JSONException e) {
            LOGGER.error("Error highlighting trajectory segment", e);
        }
    }

    /**
     * Paints the trajectory on the map, ensuring each segment gets a unique ID.
     */
    private void paintTrajectoryByActivity(Style style, String trajectory, Map<String, String> activityColors, String mode) {
        logActivityTypes(trajectory);
        JSONObject sourceJson = new JSONObject();
        try {
            sourceJson.put("type", "geojson");
            sourceJson.put("data", trajectory);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        Expected<String, None> success = style.addStyleSource(
                "trajectory_" + mode,
                Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue())
        );
        LOGGER.debug("trajectory: source created " + (success.isError() ? success.getError() : "success"));

        JSONObject layerJson = new JSONObject();
        try {
            layerJson.put("id", "trajectory_layer_" + mode);
            layerJson.put("type", "line");
            layerJson.put("source", "trajectory_" + mode);
            layerJson.put("line-join", "bevel");

            this.layerId = "trajectory_layer_" + mode;


            JSONArray colorExpression = new JSONArray();
            colorExpression.put("match");
            colorExpression.put(new JSONArray().put("get").put("activity_type"));


            for (Map.Entry<String, String> entry : activityColors.entrySet()) {
                if (!entry.getKey().equals("default")) {
                    colorExpression.put(entry.getKey());
                    colorExpression.put(entry.getValue());
                }
            }


            colorExpression.put(activityColors.get("default"));
            baseColorExpression = colorExpression;

            JSONObject paint = new JSONObject();
            paint.put("line-color", colorExpression);
            paint.put("line-width", 6);
            layerJson.put("paint", paint);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }


        Expected<String, None> layerSuccess = style.addStyleLayer(
                Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                new LayerPosition(null, null, null)
        );
        LOGGER.debug("trajectory: layer created " + (layerSuccess.isError() ? layerSuccess.getError() : "success"));

        layerNames.add(mode);
    }

    /**
     * Paints the trajectory on the map, coloring segments by trip number:
     * trip == 0 gets one color, any other trip number gets the other.
     */
    private void paintTrajectoryByUserId(Style style, TrajectoryByDate trajectoryArr, Map<String, String> tripColors, String mode) {
        String trajectory = trajectoryArr.getTrajectoryStr();
        JSONObject sourceJson = new JSONObject();
        try {
            sourceJson.put("type", "geojson");
            sourceJson.put("data", trajectory);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        Expected<String, None> success = style.addStyleSource(
                "trajectory_" + mode,
                Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue())
        );
        LOGGER.debug("trajectory: source created " + (success.isError() ? success.getError() : "success"));

        JSONObject layerJson = new JSONObject();
        try {
            layerJson.put("id", "trajectory_layer_" + mode);
            layerJson.put("type", "line");
            layerJson.put("source", "trajectory_" + mode);
            layerJson.put("line-join", "bevel");

            this.layerId = "trajectory_layer_" + mode;

            JSONArray colorExpression = new JSONArray();
            colorExpression.put("match");
            colorExpression.put(new JSONArray().put("get").put("trip"));

            for (Map.Entry<String, String> entry : tripColors.entrySet()) {
                if (!entry.getKey().equals("default")) {
                    // "trip" is numeric in the GeoJSON, so the match case must be a numeric literal, not a string
                    colorExpression.put(Integer.parseInt(entry.getKey()));
                    colorExpression.put(entry.getValue());
                }
            }

            colorExpression.put(tripColors.get("default"));
            baseColorExpression = colorExpression;

            JSONObject paint = new JSONObject();
            paint.put("line-color", colorExpression);
            paint.put("line-width", 6);
            layerJson.put("paint", paint);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        Expected<String, None> layerSuccess = style.addStyleLayer(
                Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                new LayerPosition(null, null, null)
        );
        LOGGER.debug("trajectory: layer created " + (layerSuccess.isError() ? layerSuccess.getError() : "success"));

        layerNames.add(mode);
    }

    private void resetCameraCentre(MapView mapView, JSONArray bbox) {
        mapView.getMapboxMap().cameraAnimationsPlugin(plugin -> {
            Point newCenter = getBBoxCenter(bbox);
            if (newCenter == null) {
                return null;
            }
            plugin.flyTo(new CameraOptions.Builder()
                            .center(newCenter)
                            .build(),
                    new MapAnimationOptions.Builder().duration(2000).build(),
                    null);
            return null;
        });
    }

    private void resetCameraCentre(MapView mapView, TrajectoryByDate trajectoryByDate) {
        try {
            JSONObject jsonObject = new JSONObject(trajectoryByDate.getTrajectoryStr());
            JSONArray bbox = jsonObject.getJSONArray("bbox");

            resetCameraCentre(mapView, bbox);
        } catch (JSONException e) {
            LOGGER.info("No trajectory retrieved, no need to reset camera");
        }
    }

    private Point getBBoxCenter(JSONArray bbox) {
        try {
            double lngAvg = (bbox.getDouble(0) + bbox.getDouble(2)) / 2;
            double latAvg = (bbox.getDouble(1) + bbox.getDouble(3)) / 2;
            return Point.fromLngLat(lngAvg, latAvg);
        } catch (JSONException e) {
            return null;
        }
    }

    private void removeAllLayers(Style style) {
        try {

            List<String> failureLayers = new ArrayList<>();
            List<String> failureSources = new ArrayList<>();

            if (style.styleLayerExists("highlight_layer")) {
                style.removeStyleLayer("highlight_layer");
                LOGGER.debug("Removed highlight_layer");
            }

            for (String name : layerNames) {
                String layerName = "trajectory_layer_" + name;
                String sourceName = "trajectory_" + name;

                Expected<String, None> layerResult = style.removeStyleLayer(layerName);
                Expected<String, None> sourceResult = style.removeStyleSource(sourceName);

                if (layerResult.isError()) failureLayers.add(layerName);
                if (sourceResult.isError()) failureSources.add(sourceName);
                else LOGGER.debug("Removed layer and source: " + layerName + ", " + sourceName);
            }

            if (!failureLayers.isEmpty() || !failureSources.isEmpty()) {
                LOGGER.error("Failed to remove layers: " + String.join(", ", failureLayers));
                LOGGER.error("Failed to remove sources: " + String.join(", ", failureSources));
            }

            layerNames.clear();

        } catch (Exception e) {
            LOGGER.error("Exception while removing layers and sources", e);
        }

    }
}