package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import uk.ac.cam.cares.jps.timeline.model.trajectory.ActivityTrajectory;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectoryByDate;
import uk.ac.cam.cares.jps.timeline.model.trajectory.TrajectorySegment;
import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.TrajectoryViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.DisplayOptionsViewModel;   // CHANGE: ADD import (lives in core:ui-impl)
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
    // CHANGE: the map button is gone, the choice now comes from the Display Options screen through this ViewModel
    private final DisplayOptionsViewModel displayOptionsViewModel;
    private final MapView mapView;                   // needed by setTrajectoryVisible() outside the constructor
    private boolean trajectoryVisible = true;
    private final TripAgentViewModel tripAgentViewModel;
    private static final String HIGHLIGHT_SOURCE = "highlight_source";
    private static final double ACTIVITY_FADE_OPACITY = 0.3;     // other activity segments while one is selected
    private static final double MATCH_TOLERANCE = 0.0001;         // ~11 m; activity points sit on the trips geometry
    private String activityGeojsonPainted;                        // normalised GeoJSON currently in the activity layer
    private boolean paintedActivity = false;   // true while the map shows the activity layer (not the trips layer)
    private String selectedColor;


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
        // CHANGE: activity-scoped, so it is the same instance DisplayOptionsFragment writes to
        displayOptionsViewModel = new ViewModelProvider(fragment.requireActivity()).get(DisplayOptionsViewModel.class);
        this.mapView = mapView;

        activityColors.put("walking", "#22A06B");
        activityColors.put("still", "#8B929C");
        activityColors.put("vehicle", "#3977D5");
        activityColors.put("bike", "#E99A27");
        activityColors.put("lost_signal", "#E53935");
        activityColors.put("default", "#B0BEC5");

        // trip == 0 (stationary/stay segment) gets one color
        tripColors.put("0", "#FF0000");
        // any other trip number gets the other color
        tripColors.put("default", "#008000");

        String selectedColor = getColorHex(fragment.requireContext(), R.attr.colorSelected);
        this.selectedColor = selectedColor;

        // TrajectoryManager's click listener — back to just passing the date
        fragment.requireView().findViewById(R.id.run_trip_agent_button)
                .setOnClickListener(v -> tripAgentViewModel.runTripAgent(normalBottomSheetViewModel.selectedDate.getValue()));

        // run the trip agent and then the exposure calculation every 5 minutes while this screen is visible
        new AutoTripRunner(fragment.getViewLifecycleOwner(), tripAgentViewModel, normalBottomSheetViewModel);

        // colouring by trips/visits or by activity, chosen on the Display Options screen
        legendRoot = fragment.requireView();
        // start from whatever was already chosen (the ViewModel outlives this manager)
        colorMode = toColorMode(displayOptionsViewModel.getTrajectoryView());
        updateModeUi();
        // activity-split data arrives separately, only when Activity mode needs it
        trajectoryViewModel.activityTrajectory.observe(fragment.getViewLifecycleOwner(), data -> {
            if (data == null || currentTrajectory == null || !data.getDate().equals(currentTrajectory.getDate())) {
                return;
            }
            activityTrajectory = data;
            if (colorMode == ColorMode.ACTIVITY) {
                mapView.getMapboxMap().getStyle(style -> {
                    paintCurrentTrajectory(style);
                    applySelection(style, trajectoryViewModel.clickedSegment.getValue());
                });
            }
        });
        trajectoryViewModel.activityTrajectoryError.observe(fragment.getViewLifecycleOwner(), error -> {
            if (error == null) {
                return;
            }
            activityFetchDate = null;
            if (colorMode == ColorMode.ACTIVITY) {
                Toast.makeText(mapView.getContext(), "Could not load activity data", Toast.LENGTH_SHORT).show();
                // CHANGE: fall back through the ViewModel so the toggle on the Display Options screen shows Trips & visits
                // again too. The observer below then switches the mode, legend and repaints the map.
                displayOptionsViewModel.setTrajectoryView(DisplayOptionsViewModel.TrajectoryView.TRIPS_AND_VISITS);
            }
        });

        // CHANGE: replaces modeButton.setOnClickListener. Reacts to the choice made on the Display Options screen.
        displayOptionsViewModel.trajectoryView.observe(fragment.getViewLifecycleOwner(), view -> {
            ColorMode newMode = toColorMode(view);
            if (newMode == colorMode) {
                return;   // first emission, or nothing actually changed
            }
            colorMode = newMode;
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

                            if (paintedActivity) {
                                // Activity features have their own ids, so map the tap onto the trips
                                // segment the bottom sheet knows about and select that one.
                                TrajectorySegment tripSegment = resolveTripSegment(feature, point);
                                if (tripSegment != null) {
                                    trajectoryViewModel.setClickedSegment(tripSegment.getId(), tripSegment.getSessionId());
                                } else {
                                    trajectoryViewModel.setClickedSegment(null, null);
                                }
                                return;
                            }

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
            applySelection(mapView.getMapboxMap().getStyle(), clickedSegment);
            if (clickedSegment != null) {
                resetCameraCentre(mapView, clickedSegment.getBbox());
            }
        });
    }

    /** Shows the selection on the map, in whichever layer (trips or activity) is currently painted. */
    private void applySelection(Style style, TrajectorySegment segment) {
        if (style == null) {
            return;
        }
        removeHighlightLayer(style);
        if (paintedActivity) {
            showSelectedActivityPieces(style, segment);
        } else {
            updateSessionGreyOut(style, segment);
            if (segment != null) {
                highlightTrajectorySegment(style, segment, selectedColor);
            }
        }
    }

    /**
     * Activity features have no ids that match the trips data, so a tap on the activity layer is
     * resolved to a trips segment. If the tapped feature carries start_time/end_time, only trips
     * segments that overlap that time range are considered; the one whose geometry passes closest
     * to the tap wins. Without times it is purely the closest geometry.
     */
    private TrajectorySegment resolveTripSegment(Feature feature, Point tap) {
        if (currentTrajectory == null) {
            return null;
        }
        List<TrajectorySegment> candidates = new ArrayList<>(currentTrajectory.getTrajectorySegments());
        Long start = readLongProperty(feature, "start_time");
        Long end = readLongProperty(feature, "end_time");
        if (start != null && end != null && end > start) {
            List<TrajectorySegment> overlapping = new ArrayList<>();
            for (TrajectorySegment segment : candidates) {
                if (Math.min(end, segment.getEndTime()) > Math.max(start, segment.getStartTime())) {
                    overlapping.add(segment);
                }
            }
            if (!overlapping.isEmpty()) {
                candidates = overlapping;
            }
        }

        double cosLat = Math.cos(Math.toRadians(tap.latitude()));
        TrajectorySegment best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TrajectorySegment segment : candidates) {
            double distance = distanceToGeometry(segment.getGeom(), tap.longitude(), tap.latitude(), cosLat);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment;
            }
        }
        return best;
    }

    private Long readLongProperty(Feature feature, String key) {
        try {
            if (feature.hasProperty(key)) {
                return feature.getNumberProperty(key).longValue();
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Property " + key + " is not numeric on the tapped feature");
        }
        return null;
    }

    /** Distance from a point to a GeoJSON LineString/MultiLineString/Point, in degrees (longitude scaled by cosLat). */
    private double distanceToGeometry(JSONObject geom, double lng, double lat, double cosLat) {
        if (geom == null) {
            return Double.MAX_VALUE;
        }
        try {
            String type = geom.optString("type");
            JSONArray coordinates = geom.getJSONArray("coordinates");
            if ("LineString".equals(type)) {
                return distanceToLine(coordinates, lng, lat, cosLat);
            }
            if ("MultiLineString".equals(type)) {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < coordinates.length(); i++) {
                    best = Math.min(best, distanceToLine(coordinates.getJSONArray(i), lng, lat, cosLat));
                }
                return best;
            }
            if ("Point".equals(type)) {
                return Math.hypot(coordinates.getDouble(0) * cosLat - lng * cosLat, coordinates.getDouble(1) - lat);
            }
        } catch (JSONException e) {
            LOGGER.debug("Could not read segment geometry for tap matching");
        }
        return Double.MAX_VALUE;
    }

    private double distanceToLine(JSONArray line, double lng, double lat, double cosLat) throws JSONException {
        double px = lng * cosLat;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < line.length(); i++) {
            JSONArray a = line.getJSONArray(i);
            double ax = a.getDouble(0) * cosLat;
            double ay = a.getDouble(1);
            if (i + 1 < line.length()) {
                JSONArray b = line.getJSONArray(i + 1);
                best = Math.min(best, distanceToSegment(px, lat, ax, ay, b.getDouble(0) * cosLat, b.getDouble(1)));
            } else if (line.length() == 1) {
                best = Math.hypot(px - ax, lat - ay);
            }
        }
        return best;
    }

    private double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /**
     * Activity mode selection: fade the whole activity layer and draw only the activity pieces that belong
     * to the selected trips segment on top at full opacity, with the normal activity colours (no highlight
     * colour). If no piece can be matched, nothing is faded so the map never ends up all faded.
     */
    private void showSelectedActivityPieces(Style style, TrajectorySegment segment) {
        if (layerId == null || !style.styleLayerExists(layerId)) {
            return;
        }
        if (segment == null) {
            setLayerProperty(style, "line-opacity", 1.0);
            return;
        }
        JSONArray pieces = selectActivityPieces(segment);
        if (pieces.length() == 0) {
            setLayerProperty(style, "line-opacity", 1.0);
            return;
        }
        try {
            JSONObject collection = new JSONObject().put("type", "FeatureCollection").put("features", pieces);
            JSONObject sourceJson = new JSONObject().put("type", "geojson").put("data", collection.toString());
            style.addStyleSource(HIGHLIGHT_SOURCE, Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue()));

            JSONObject layerJson = new JSONObject()
                    .put("id", "highlight_layer")
                    .put("type", "line")
                    .put("source", HIGHLIGHT_SOURCE)
                    .put("paint", new JSONObject().put("line-color", baseColorExpression).put("line-width", 6));
            style.addStyleLayer(Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                    new LayerPosition(null, null, null));
            setLayerProperty(style, "line-opacity", ACTIVITY_FADE_OPACITY);
        } catch (JSONException e) {
            LOGGER.error("Error showing selected activity pieces", e);
        }
    }

    /**
     * Picks the painted activity features that belong to a trips segment: by time overlap when the features
     * carry start_time/end_time, otherwise (or if that matches nothing) by lying on the segment's geometry.
     */
    private JSONArray selectActivityPieces(TrajectorySegment segment) {
        JSONArray byTime = new JSONArray();
        JSONArray byGeometry = new JSONArray();
        if (activityGeojsonPainted == null) {
            return byTime;
        }
        try {
            JSONArray features = new JSONObject(activityGeojsonPainted).optJSONArray("features");
            if (features == null) {
                return byTime;
            }
            double cosLat = 1.0;
            double[] first = firstVertex(segment.getGeom());
            if (first != null) {
                cosLat = Math.cos(Math.toRadians(first[1]));
            }
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject props = feature.optJSONObject("properties");
                if (props != null) {
                    long start = props.optLong("start_time", 0);
                    long end = props.optLong("end_time", 0);
                    if (start > 0 && end > start
                            && Math.min(end, segment.getEndTime()) > Math.max(start, segment.getStartTime())) {
                        byTime.put(feature);
                    }
                }
                if (liesOnGeometry(feature.optJSONObject("geometry"), segment.getGeom(), cosLat)) {
                    byGeometry.put(feature);
                }
            }
        } catch (JSONException e) {
            LOGGER.error("Could not match activity pieces to the selected segment", e);
        }
        return byTime.length() > 0 ? byTime : byGeometry;
    }

    /** True if at least two of a feature's first/middle/last vertices lie within MATCH_TOLERANCE of the geometry. */
    private boolean liesOnGeometry(JSONObject featureGeom, JSONObject segmentGeom, double cosLat) throws JSONException {
        List<double[]> vertices = flattenVertices(featureGeom);
        if (vertices.isEmpty() || segmentGeom == null) {
            return false;
        }
        int[] samples = {0, vertices.size() / 2, vertices.size() - 1};
        int near = 0;
        int counted = 0;
        int last = -1;
        for (int index : samples) {
            if (index == last) {
                continue;   // short features: do not count the same vertex twice
            }
            last = index;
            counted++;
            double[] v = vertices.get(index);
            if (distanceToGeometry(segmentGeom, v[0], v[1], cosLat) <= MATCH_TOLERANCE) {
                near++;
            }
        }
        return near >= Math.min(2, counted);
    }

    private List<double[]> flattenVertices(JSONObject geom) throws JSONException {
        List<double[]> out = new ArrayList<>();
        if (geom == null) {
            return out;
        }
        String type = geom.optString("type");
        JSONArray coordinates = geom.optJSONArray("coordinates");
        if (coordinates == null) {
            return out;
        }
        if ("LineString".equals(type)) {
            for (int i = 0; i < coordinates.length(); i++) {
                JSONArray c = coordinates.getJSONArray(i);
                out.add(new double[]{c.getDouble(0), c.getDouble(1)});
            }
        } else if ("MultiLineString".equals(type)) {
            for (int p = 0; p < coordinates.length(); p++) {
                JSONArray part = coordinates.getJSONArray(p);
                for (int i = 0; i < part.length(); i++) {
                    JSONArray c = part.getJSONArray(i);
                    out.add(new double[]{c.getDouble(0), c.getDouble(1)});
                }
            }
        } else if ("Point".equals(type)) {
            out.add(new double[]{coordinates.getDouble(0), coordinates.getDouble(1)});
        }
        return out;
    }

    private double[] firstVertex(JSONObject geom) {
        try {
            List<double[]> vertices = flattenVertices(geom);
            return vertices.isEmpty() ? null : vertices.get(0);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Adds the dominant activity from activity_proportions when the layer has no flat
     * activity_type property, then logs the values used for map colouring.
     */
    private String normalizeActivityTypes(String trajectory) {
        try {
            JSONObject geojson = new JSONObject(trajectory);
            JSONArray features = geojson.getJSONArray("features");
            Map<String, Integer> counts = new HashMap<>();
            Map<String, Integer> propertyKeyCounts = new HashMap<>();
            Map<String, Integer> proportionKeyCounts = new HashMap<>();
            Map<String, Integer> directActivityTypes = new HashMap<>();
            Map<String, Integer> proportionValueTypes = new HashMap<>();
            for (int i = 0; i < features.length(); i++) {
                JSONObject properties = features.getJSONObject(i).optJSONObject("properties");
                if (properties == null) {
                    counts.merge("<no properties>", 1, Integer::sum);
                    continue;
                }

                Iterator<String> propertyKeys = properties.keys();
                while (propertyKeys.hasNext()) {
                    propertyKeyCounts.merge(propertyKeys.next(), 1, Integer::sum);
                }

                Object rawActivityType = getValueIgnoreCase(properties, "activity_type");
                String type = rawActivityType == null || rawActivityType == JSONObject.NULL
                        ? ""
                        : canonicalActivityType(rawActivityType.toString());
                if (!type.isEmpty()) {
                    directActivityTypes.merge(type, 1, Integer::sum);
                }
                if (type.isEmpty() || type.equals("unknown") || type.equals("null")) {
                    Object rawProportions = getValueIgnoreCase(properties, "activity_proportions");
                    if (rawProportions != null && rawProportions != JSONObject.NULL) {
                        proportionValueTypes.merge(rawProportions.getClass().getSimpleName(), 1, Integer::sum);
                    }
                    JSONObject proportions = parseActivityProportions(rawProportions);
                    if (proportions != null) {
                        Iterator<String> proportionKeys = proportions.keys();
                        while (proportionKeys.hasNext()) {
                            proportionKeyCounts.merge(proportionKeys.next(), 1, Integer::sum);
                        }
                    }
                    type = getDominantActivityType(proportions);
                    if (type != null) {
                        properties.put("activity_type", type);
                    } else {
                        type = "<missing>";
                    }
                } else {
                    properties.put("activity_type", type);
                }
                counts.merge(type, 1, Integer::sum);
            }
            LOGGER.info("Activity colouring: activity_type values in data = " + counts);
            LOGGER.info("Activity GeoJSON schema: propertyKeys=" + propertyKeyCounts
                    + ", directActivityTypes=" + directActivityTypes
                    + ", proportionKeys=" + proportionKeyCounts
                    + ", proportionValueTypes=" + proportionValueTypes);
            return geojson.toString();
        } catch (JSONException e) {
            LOGGER.error("Could not normalize activity types in trajectory data", e);
            return trajectory;
        }
    }

    private Object getValueIgnoreCase(JSONObject object, String key) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String actualKey = keys.next();
            if (actualKey.equalsIgnoreCase(key)) {
                return object.opt(actualKey);
            }
        }
        return null;
    }

    private JSONObject parseActivityProportions(Object rawProportions) {
        if (rawProportions instanceof JSONObject) {
            return (JSONObject) rawProportions;
        }
        if (rawProportions instanceof String) {
            try {
                return new JSONObject((String) rawProportions);
            } catch (JSONException e) {
                LOGGER.warn("Could not parse activity_proportions JSON string", e);
            }
        }
        return null;
    }

    /**
     * Maps names the backend may send (Google-style IN_VEHICLE, ON_BICYCLE, ON_FOOT, ...) onto the labels
     * used for colouring: walking, still, vehicle, bike. Anything else is returned lower-cased and unchanged.
     */
    private String canonicalActivityType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (t) {
            case "in_vehicle":
            case "invehicle":
            case "driving":
            case "car":
                return "vehicle";
            case "on_bicycle":
            case "bicycle":
            case "cycling":
                return "bike";
            case "no_signal":
            case "signal_lost":
                return "lost_signal";
            case "on_foot":
            case "walk":
            case "running":
                return "walking";
            default:
                return t;
        }
    }

    private String getDominantActivityType(JSONObject proportions) {
        if (proportions == null) {
            return null;
        }

        // sum per canonical type, so e.g. WALKING + ON_FOOT count together
        Map<String, Double> totals = new HashMap<>();
        Iterator<String> activityTypes = proportions.keys();
        while (activityTypes.hasNext()) {
            String rawActivityType = activityTypes.next();
            String activityType = canonicalActivityType(rawActivityType);
            Object value = proportions.opt(rawActivityType);
            double proportion;
            if (value instanceof Number) {
                proportion = ((Number) value).doubleValue();
            } else if (value instanceof String) {
                try {
                    proportion = Double.parseDouble((String) value);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid proportion for activity type " + activityType + ": " + value, e);
                    continue;
                }
            } else {
                continue;
            }
            if (!Double.isNaN(proportion) && !Double.isInfinite(proportion)) {
                totals.merge(activityType, proportion, Double::sum);
            }
        }

        String dominantType = null;
        double largestProportion = 0;
        for (Map.Entry<String, Double> entry : totals.entrySet()) {
            if (activityColors.containsKey(entry.getKey()) && entry.getValue() > largestProportion) {
                largestProportion = entry.getValue();
                dominantType = entry.getKey();
            }
        }
        return dominantType;
    }

    /** Repaints the current day's trajectory in the selected colour mode. */
    private void paintCurrentTrajectory(Style style) {
        removeAllLayers(style);
        paintedActivity = false;
        if (currentTrajectory == null || currentTrajectory.getTrajectoryStr().isEmpty()) {
            return;
        }
        if (colorMode == ColorMode.ACTIVITY) {
            // The trips layer has no activity_type, so activity colouring uses its own data.
            LocalDate date = currentTrajectory.getDate();
            if (activityTrajectory != null && date.equals(activityTrajectory.getDate())
                    && !activityTrajectory.getGeojson().isEmpty()) {
                paintTrajectoryByActivity(style, activityTrajectory.getGeojson(), activityColors, "default");
                paintedActivity = true;
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

    /** Maps the shared ViewModel's choice onto this class's colour mode. */
    private static ColorMode toColorMode(DisplayOptionsViewModel.TrajectoryView view) {
        return view == DisplayOptionsViewModel.TrajectoryView.ACTIVITY ? ColorMode.ACTIVITY : ColorMode.TRIP;
    }

    /** Updates the legend to match the colour mode. (The toggle itself now lives on the Display Options screen.) */
    private void updateModeUi() {
        boolean activity = colorMode == ColorMode.ACTIVITY;

        String[] labels = activity
                ? new String[]{"Walking", "Still", "Vehicle", "Bicycle", "Lost signal"}
                : new String[]{"Visit", "Trip", "", "", ""};
        String[] colors = activity
                ? new String[]{activityColors.get("walking"), activityColors.get("still"),
                activityColors.get("vehicle"), activityColors.get("bike"), activityColors.get("lost_signal")}
                : new String[]{tripColors.get("0"), tripColors.get("default"), null, null, null};
        // the card grows when it shows two lines, lift it a little so it stays clear of the sheet
        View legendCard = legendRoot.findViewById(R.id.legend_card);
        if (legendCard != null) {
            float density = legendRoot.getResources().getDisplayMetrics().density;
            legendCard.setTranslationY((activity ? -148 : -108) * density);
        }
        int[] rows = {R.id.legend_row_1, R.id.legend_row_2, R.id.legend_row_3, R.id.legend_row_4, R.id.legend_row_5};
        int[] swatches = {R.id.legend_swatch_1, R.id.legend_swatch_2, R.id.legend_swatch_3, R.id.legend_swatch_4, R.id.legend_swatch_5};
        int[] texts = {R.id.legend_text_1, R.id.legend_text_2, R.id.legend_text_3, R.id.legend_text_4, R.id.legend_text_5};
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
        if (style == null) {
            return;
        }
        if (style.styleLayerExists("highlight_layer")) {
            style.removeStyleLayer("highlight_layer");
        }
        if (style.styleSourceExists(HIGHLIGHT_SOURCE)) {
            style.removeStyleSource(HIGHLIGHT_SOURCE);
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
        trajectory = normalizeActivityTypes(trajectory);
        activityGeojsonPainted = trajectory;
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

            removeHighlightLayer(style);

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