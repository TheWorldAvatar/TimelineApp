package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import com.mapbox.bindgen.Value;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.LayerPosition;
import com.mapbox.maps.MapView;
import com.mapbox.maps.Style;
import com.mapbox.maps.plugin.animation.MapAnimationOptions;
import com.mapbox.maps.plugin.gestures.GesturesUtils;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import uk.ac.cam.cares.jps.model.GeocodingResult;
import uk.ac.cam.cares.jps.model.RouteOption;
import uk.ac.cam.cares.jps.timeline.viewmodel.RoutesViewModel;
import uk.ac.cam.cares.jps.timelinemap.R;

// NEW FILE
/**
 * Map side of route selection mode, in the same style as TrajectoryManager:
 *  - draws each suggested route as its own GeoJSON source + line layer (selected route on top and thicker)
 *  - draws the start and end points as coloured circles
 *  - turns map taps into start/end points (only while in route selection mode)
 *  - zooms the camera to fit the routes when a new result arrives
 * Everything is removed again when the screen goes back to recording mode.
 */
public class RouteManager {
    private static final Logger LOGGER = Logger.getLogger(RouteManager.class);

    private static final String ROUTE_SOURCE_PREFIX = "route_source_";
    private static final String ROUTE_LAYER_PREFIX = "route_layer_";
    private static final String POINTS_SOURCE = "route_points_source";
    private static final String POINTS_LAYER = "route_points_layer";
    private static final String OTHER_ROUTE_COLOR = "#8A8A8A";
    private static final String START_COLOR = "#2E7D32";
    private static final String END_COLOR = "#C62828";

    private final MapView mapView;
    private final RoutesViewModel routesViewModel;
    private final String selectedColor;

    // Remember what we added so we can remove exactly that on the next redraw.
    private final List<String> drawnLayerIds = new ArrayList<>();
    private final List<String> drawnSourceIds = new ArrayList<>();

    public RouteManager(Fragment fragment, MapView mapView) {
        this.mapView = mapView;
        this.routesViewModel = new ViewModelProvider(fragment.requireActivity()).get(RoutesViewModel.class);
        // Same theme colour TrajectoryManager uses for a selected trajectory segment.
        this.selectedColor = getColorHex(fragment.requireContext(), R.attr.colorSelected);

        LifecycleOwner owner = fragment.getViewLifecycleOwner();

        // Anything that changes what should be on the map triggers a full redraw (cheap: at most 3 lines + 2 points).
        routesViewModel.mode.observe(owner, mode -> redraw());
        routesViewModel.start.observe(owner, point -> redraw());
        routesViewModel.end.observe(owner, point -> redraw());
        routesViewModel.selectedRouteIndex.observe(owner, index -> redraw());
        routesViewModel.routes.observe(owner, routes -> {
            redraw();
            // Only move the camera when a new set of routes arrives, not when the user just picks another card.
            if (routes != null && !routes.isEmpty() && routesViewModel.isRouteMode()) {
                fitCameraToRoutes(routes);
            }
        });

        registerMapTapListener();
    }

    // ------------------------------------------------------------------ map taps

    private void registerMapTapListener() {
        GesturesUtils.getGestures(mapView).addOnMapClickListener(point -> {
            // Not in route mode: return false so the click reaches TrajectoryManager's listener as before.
            if (!routesViewModel.isRouteMode()) {
                return false;
            }
            routesViewModel.onMapTapped(point.longitude(), point.latitude());
            return true;
        });
    }

    // ------------------------------------------------------------------ drawing

    private void redraw() {
        mapView.getMapboxMap().getStyle(style -> {
            removeDrawn(style);
            if (!routesViewModel.isRouteMode()) {
                return; // recording mode: leave the map clean
            }

            List<RouteOption> routes = routesViewModel.routes.getValue();
            Integer selected = routesViewModel.selectedRouteIndex.getValue();
            int selectedIndex = selected == null ? 0 : selected;

            if (routes != null) {
                // Unselected routes first, so the selected one is drawn on top of them.
                for (RouteOption route : routes) {
                    if (route.getIndex() != selectedIndex) addRouteLine(style, route, false);
                }
                for (RouteOption route : routes) {
                    if (route.getIndex() == selectedIndex) addRouteLine(style, route, true);
                }
            }

            addEndpoints(style, routesViewModel.start.getValue(), routesViewModel.end.getValue());
        });
    }

    private void addRouteLine(Style style, RouteOption route, boolean selected) {
        String sourceId = ROUTE_SOURCE_PREFIX + route.getIndex();
        String layerId = ROUTE_LAYER_PREFIX + route.getIndex();

        try {
            // Build a GeoJSON Feature with a LineString. Coordinates are already [lon, lat] as Mapbox wants.
            JSONArray coordinates = new JSONArray();
            for (double[] c : route.getCoordinates()) {
                coordinates.put(new JSONArray().put(c[0]).put(c[1]));
            }
            JSONObject feature = new JSONObject()
                    .put("type", "Feature")
                    .put("properties", new JSONObject())
                    .put("geometry", new JSONObject().put("type", "LineString").put("coordinates", coordinates));

            // Same technique as TrajectoryManager: the GeoJSON is passed as a string in "data".
            JSONObject sourceJson = new JSONObject().put("type", "geojson").put("data", feature.toString());
            style.addStyleSource(sourceId, Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue()));
            drawnSourceIds.add(sourceId);

            JSONObject layout = new JSONObject().put("line-join", "round").put("line-cap", "round");
            JSONObject paint = new JSONObject()
                    .put("line-color", selected ? selectedColor : OTHER_ROUTE_COLOR)
                    .put("line-width", selected ? 9 : 5);
            JSONObject layerJson = new JSONObject()
                    .put("id", layerId)
                    .put("type", "line")
                    .put("source", sourceId)
                    .put("layout", layout)
                    .put("paint", paint);
            style.addStyleLayer(Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                    new LayerPosition(null, null, null));
            drawnLayerIds.add(layerId);
        } catch (JSONException e) {
            LOGGER.error("Could not draw route " + route.getIndex(), e);
        }
    }

    private void addEndpoints(Style style, GeocodingResult start, GeocodingResult end) {
        if (start == null && end == null) {
            return;
        }

        try {
            JSONArray features = new JSONArray();
            if (start != null) features.put(pointFeature(start, "start"));
            if (end != null) features.put(pointFeature(end, "end"));
            JSONObject collection = new JSONObject().put("type", "FeatureCollection").put("features", features);

            JSONObject sourceJson = new JSONObject().put("type", "geojson").put("data", collection.toString());
            style.addStyleSource(POINTS_SOURCE, Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue()));
            drawnSourceIds.add(POINTS_SOURCE);

            // Circle colour depends on the feature's "role" property: green for start, red for end.
            JSONArray colorExpression = new JSONArray()
                    .put("match")
                    .put(new JSONArray().put("get").put("role"))
                    .put("start").put(START_COLOR)
                    .put("end").put(END_COLOR)
                    .put("#000000");

            JSONObject paint = new JSONObject()
                    .put("circle-radius", 9)
                    .put("circle-color", colorExpression)
                    .put("circle-stroke-width", 3)
                    .put("circle-stroke-color", "#FFFFFF");
            JSONObject layerJson = new JSONObject()
                    .put("id", POINTS_LAYER)
                    .put("type", "circle")
                    .put("source", POINTS_SOURCE)
                    .put("paint", paint);
            style.addStyleLayer(Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                    new LayerPosition(null, null, null)); // added last, so the circles sit on top of the lines
            drawnLayerIds.add(POINTS_LAYER);
        } catch (JSONException e) {
            LOGGER.error("Could not draw start/end points", e);
        }
    }

    private JSONObject pointFeature(GeocodingResult point, String role) throws JSONException {
        return new JSONObject()
                .put("type", "Feature")
                .put("properties", new JSONObject().put("role", role))
                .put("geometry", new JSONObject()
                        .put("type", "Point")
                        .put("coordinates", new JSONArray().put(point.getLongitude()).put(point.getLatitude())));
    }

    private void removeDrawn(Style style) {
        // Layers must be removed before the sources they use.
        for (String layerId : drawnLayerIds) {
            style.removeStyleLayer(layerId);
        }
        for (String sourceId : drawnSourceIds) {
            style.removeStyleSource(sourceId);
        }
        drawnLayerIds.clear();
        drawnSourceIds.clear();
    }

    // ------------------------------------------------------------------ camera

    /**
     * Centres the map on the routes and picks a zoom that fits them.
     * Done with plain maths (centre + zoom) so it only relies on the flyTo call TrajectoryManager already uses.
     * The map is 512 dp wide at zoom 0 and doubles each zoom level, so: zoom = log2(available dp * 360 / (512 * degrees)).
     * Latitude uses the Mercator projection, which stretches the map away from the equator.
     */
    private void fitCameraToRoutes(List<RouteOption> routes) {
        double minLon = 180, maxLon = -180, minLat = 90, maxLat = -90;
        for (RouteOption route : routes) {
            for (double[] c : route.getCoordinates()) {
                minLon = Math.min(minLon, c[0]);
                maxLon = Math.max(maxLon, c[0]);
                minLat = Math.min(minLat, c[1]);
                maxLat = Math.max(maxLat, c[1]);
            }
        }

        float density = mapView.getResources().getDisplayMetrics().density;
        if (mapView.getWidth() == 0 || mapView.getHeight() == 0) {
            return; // not laid out yet
        }
        double widthDp = mapView.getWidth() / density;
        double heightDp = mapView.getHeight() / density;

        // Only part of the map is free: the route panel covers the top and the result cards the bottom.
        double usableWidth = widthDp * 0.8;
        double usableHeight = heightDp * 0.35;

        double lonSpan = Math.max(maxLon - minLon, 0.001);
        double mercatorSpan = Math.max(mercatorY(maxLat) - mercatorY(minLat), 0.00001);

        double zoomForWidth = log2((360.0 * usableWidth) / (512.0 * lonSpan));
        double zoomForHeight = log2((2 * Math.PI * usableHeight) / (512.0 * mercatorSpan));
        double zoom = Math.max(3.0, Math.min(17.0, Math.min(zoomForWidth, zoomForHeight)));

        Point center = Point.fromLngLat((minLon + maxLon) / 2, (minLat + maxLat) / 2);

        // Same call pattern as TrajectoryManager.resetCameraCentre().
        mapView.getMapboxMap().cameraAnimationsPlugin(plugin -> {
            plugin.flyTo(new CameraOptions.Builder().center(center).zoom(zoom).build(),
                    new MapAnimationOptions.Builder().duration(1500).build(),
                    null);
            return null;
        });
    }

    private double mercatorY(double latitudeDegrees) {
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latitudeDegrees) / 2));
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    // Same helper as in TrajectoryManager: reads a theme colour attribute as "#RRGGBB".
    private String getColorHex(Context context, int colorAttr) {
        TypedArray typedArray = context.getTheme().obtainStyledAttributes(new int[]{colorAttr});
        int color = typedArray.getColor(0, Color.BLACK);
        typedArray.recycle();
        return String.format("#%06X", (0xFFFFFF & color));
    }
}