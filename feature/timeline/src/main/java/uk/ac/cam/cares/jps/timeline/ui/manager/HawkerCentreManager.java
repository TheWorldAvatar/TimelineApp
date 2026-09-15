package uk.ac.cam.cares.jps.timeline.ui.manager;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.mapbox.bindgen.Expected;
import com.mapbox.bindgen.None;
import com.mapbox.bindgen.Value;
import com.mapbox.maps.MapView;
import com.mapbox.maps.Style;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import uk.ac.cam.cares.jps.timeline.viewmodel.HawkerCentreViewModel;

/**
 * UI manager for the hawker centre point layer. Loading is triggered explicitly
 * (e.g. via a button click) rather than automatically on fragment creation.
 */
public class HawkerCentreManager {

    private static final String SOURCE_ID = "hawker_centres_source";
    private static final String LAYER_ID = "hawker_centres_layer";
    private final Logger LOGGER = Logger.getLogger(HawkerCentreManager.class);

    private final HawkerCentreViewModel viewModel;
    private final MapView mapView;
    private String cachedGeoJson;
    private boolean isVisible = false;

    public HawkerCentreManager(Fragment fragment, MapView mapView) {
        this.mapView = mapView;
        viewModel = new ViewModelProvider(fragment).get(HawkerCentreViewModel.class);

        viewModel.hawkerCentresGeoJson.observe(fragment.getViewLifecycleOwner(), geoJson -> {
            if (geoJson == null || geoJson.isEmpty()) return;
            cachedGeoJson = geoJson;
            if (isVisible) {
                mapView.getMapboxMap().getStyle(style -> paintHawkerCentres(style, geoJson));
            }
        });

        viewModel.error.observe(fragment.getViewLifecycleOwner(), error -> {
            if (error != null) {
                LOGGER.error("Failed to load hawker centres", error);
            }
        });

        // NOTE: no automatic fetch here anymore — call toggleHawkerCentres() from a button instead.
    }

    /** Call this from a button click. First tap fetches + shows; second tap hides. */
    public void toggleHawkerCentres() {
        if (isVisible) {
            isVisible = false;
            removeHawkerCentres();
        } else {
            isVisible = true;
            if (cachedGeoJson != null) {
                // already fetched once — just re-add the layer, no need to hit the network again
                mapView.getMapboxMap().getStyle(style -> paintHawkerCentres(style, cachedGeoJson));
            } else {
                viewModel.loadHawkerCentresIfNeeded();
            }
        }
    }

    private void removeHawkerCentres() {
        mapView.getMapboxMap().getStyle(style -> {
            if (style.styleLayerExists(LAYER_ID)) {
                style.removeStyleLayer(LAYER_ID);
            }
        });
    }

    private void paintHawkerCentres(Style style, String geoJson) {
        try {
            if (!style.styleSourceExists(SOURCE_ID)) {
                JSONObject geoJsonObject = new JSONObject(geoJson);
                JSONObject sourceJson = new JSONObject();
                sourceJson.put("type", "geojson");
                sourceJson.put("data", geoJsonObject);

                Expected<String, None> sourceResult = style.addStyleSource(
                        SOURCE_ID,
                        Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue())
                );
                LOGGER.debug("hawker centre source: " + (sourceResult.isError() ? sourceResult.getError() : "success"));
            }

            if (!style.styleLayerExists(LAYER_ID)) {
                JSONObject paint = new JSONObject();
                paint.put("circle-radius", 6);
                paint.put("circle-color", "#FF6B35");
                paint.put("circle-stroke-width", 1.5);
                paint.put("circle-stroke-color", "#FFFFFF");

                JSONObject layerJson = new JSONObject();
                layerJson.put("id", LAYER_ID);
                layerJson.put("type", "circle");
                layerJson.put("source", SOURCE_ID);
                layerJson.put("paint", paint);

                Expected<String, None> layerResult = style.addStyleLayer(
                        Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                        null
                );
                LOGGER.debug("hawker centre layer: " + (layerResult.isError() ? layerResult.getError() : "success"));
            }
        } catch (JSONException e) {
            LOGGER.error("Error painting hawker centres", e);
        }
    }
}