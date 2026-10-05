package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.mapbox.bindgen.Expected;
import com.mapbox.bindgen.None;
import com.mapbox.bindgen.Value;
import com.mapbox.maps.MapView;
import com.mapbox.maps.Style;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import uk.ac.cam.cares.jps.timeline.viewmodel.DatasetLayerViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.AppPreferenceViewModel;

/**
 * UI manager for the exposure dataset layer shown on the map. Which dataset is
 * displayed is driven by whatever dataset id is currently saved in the exposure
 * calculation settings (see ExposureSettingFragment / AppPreferenceViewModel),
 * rather than a single hardcoded layer. Datasets can contain point or polygon
 * geometry, so the render layer(s) are chosen based on what's actually present
 * in the fetched GeoJSON. Loading is triggered explicitly (button click) rather
 * than automatically on fragment creation.
 */
public class DatasetLayerManager {

    private static final String SOURCE_ID = "exposure_dataset_source";
    private static final String POINT_LAYER_ID = "exposure_dataset_point_layer";
    private static final String POLYGON_FILL_LAYER_ID = "exposure_dataset_polygon_fill_layer";
    private static final String POLYGON_OUTLINE_LAYER_ID = "exposure_dataset_polygon_outline_layer";

    private final Logger LOGGER = Logger.getLogger(DatasetLayerManager.class);

    private final Fragment fragment;
    private final DatasetLayerViewModel viewModel;
    private final AppPreferenceViewModel appPreferenceViewModel;
    private final MapView mapView;

    private String selectedDatasetId; // whatever is currently saved in settings
    private String paintedDatasetId;  // whatever is currently drawn on the map
    private boolean isVisible = false;

    public DatasetLayerManager(Fragment fragment, MapView mapView) {
        this.fragment = fragment;
        this.mapView = mapView;
        viewModel = new ViewModelProvider(fragment).get(DatasetLayerViewModel.class);
        appPreferenceViewModel = new ViewModelProvider(fragment.requireActivity()).get(AppPreferenceViewModel.class);

        // make sure the saved exposure dataset is loaded even if the settings screen
        // was never opened this session
        appPreferenceViewModel.loadExposureParams();

        appPreferenceViewModel.getExposureDatasetTableName().observe(fragment.getViewLifecycleOwner(), datasetId -> {
            selectedDatasetId = (datasetId == null || datasetId.isEmpty()) ? null : datasetId;
            // if the dataset selection changes in settings while the layer is showing,
            // refresh the map straight away to reflect the new choice
            if (isVisible && selectedDatasetId != null && !selectedDatasetId.equals(paintedDatasetId)) {
                viewModel.loadDataset(selectedDatasetId);
            }
        });

        viewModel.datasetGeoJson.observe(fragment.getViewLifecycleOwner(), geoJson -> {
            String loadedId = viewModel.loadedDatasetId.getValue();
            if (geoJson == null || geoJson.isEmpty() || loadedId == null) return;
            // ignore a response for a dataset the user has since switched away from
            if (!loadedId.equals(selectedDatasetId)) return;
            if (isVisible) {
                mapView.getMapboxMap().getStyle(style -> paintDataset(style, loadedId, geoJson));
            }
        });

        viewModel.error.observe(fragment.getViewLifecycleOwner(), error -> {
            if (error != null) {
                LOGGER.error("Failed to load exposure dataset layer", error);
            }
        });
    }

    /** Call this from a button click. First tap fetches + shows; second tap hides. */
    public void toggleDatasetLayer() {
        if (isVisible) {
            isVisible = false;
            removeDatasetLayers();
            return;
        }

        if (selectedDatasetId == null) {
            Toast.makeText(fragment.requireContext(),
                    "Pick a dataset in the exposure settings first", Toast.LENGTH_SHORT).show();
            return;
        }

        isVisible = true;
        viewModel.loadDataset(selectedDatasetId);
    }

    private void removeDatasetLayers() {
        mapView.getMapboxMap().getStyle(style -> {
            if (style.styleLayerExists(POINT_LAYER_ID)) style.removeStyleLayer(POINT_LAYER_ID);
            if (style.styleLayerExists(POLYGON_FILL_LAYER_ID)) style.removeStyleLayer(POLYGON_FILL_LAYER_ID);
            if (style.styleLayerExists(POLYGON_OUTLINE_LAYER_ID)) style.removeStyleLayer(POLYGON_OUTLINE_LAYER_ID);
        });
    }

    private void paintDataset(Style style, String datasetId, String geoJson) {
        try {
            // the previously painted dataset may have used different layer types
            // (e.g. switching from a polygon dataset to a point dataset), so tear
            // down the old layers + source before drawing the new selection
            if (!datasetId.equals(paintedDatasetId)) {
                removeDatasetLayersAndSource(style);
            }
            paintedDatasetId = datasetId;

            JSONObject geoJsonObject = new JSONObject(geoJson);
            boolean hasPolygon = containsGeometryType(geoJsonObject, "Polygon", "MultiPolygon");
            boolean hasPoint = containsGeometryType(geoJsonObject, "Point", "MultiPoint");

            if (!style.styleSourceExists(SOURCE_ID)) {
                JSONObject sourceJson = new JSONObject();
                sourceJson.put("type", "geojson");
                sourceJson.put("data", geoJsonObject);

                Expected<String, None> sourceResult = style.addStyleSource(
                        SOURCE_ID,
                        Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue())
                );
                LOGGER.debug("exposure dataset source: " + (sourceResult.isError() ? sourceResult.getError() : "success"));
            }

            if (hasPolygon) {
                addPolygonLayers(style);
            }
            if (hasPoint) {
                addPointLayer(style);
            }
            if (!hasPolygon && !hasPoint) {
                LOGGER.warn("Dataset '" + datasetId + "' has no Point/Polygon geometry to render.");
            }
        } catch (JSONException e) {
            LOGGER.error("Error painting exposure dataset layer", e);
        }
    }

    private void removeDatasetLayersAndSource(Style style) {
        if (style.styleLayerExists(POINT_LAYER_ID)) style.removeStyleLayer(POINT_LAYER_ID);
        if (style.styleLayerExists(POLYGON_FILL_LAYER_ID)) style.removeStyleLayer(POLYGON_FILL_LAYER_ID);
        if (style.styleLayerExists(POLYGON_OUTLINE_LAYER_ID)) style.removeStyleLayer(POLYGON_OUTLINE_LAYER_ID);
        if (style.styleSourceExists(SOURCE_ID)) style.removeStyleSource(SOURCE_ID);
    }

    private void addPointLayer(Style style) throws JSONException {
        if (style.styleLayerExists(POINT_LAYER_ID)) return;

        JSONObject paint = new JSONObject();
        paint.put("circle-radius", 6);
        paint.put("circle-color", "#FF6B35");
        paint.put("circle-stroke-width", 1.5);
        paint.put("circle-stroke-color", "#FFFFFF");

        JSONObject layerJson = new JSONObject();
        layerJson.put("id", POINT_LAYER_ID);
        layerJson.put("type", "circle");
        layerJson.put("source", SOURCE_ID);
        layerJson.put("paint", paint);

        Expected<String, None> layerResult = style.addStyleLayer(
                Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()), null);
        LOGGER.debug("exposure dataset point layer: " + (layerResult.isError() ? layerResult.getError() : "success"));
    }

    private void addPolygonLayers(Style style) throws JSONException {
        if (!style.styleLayerExists(POLYGON_FILL_LAYER_ID)) {
            JSONObject fillPaint = new JSONObject();
            fillPaint.put("fill-color", "#FF6B35");
            fillPaint.put("fill-opacity", 0.35);

            JSONObject fillLayerJson = new JSONObject();
            fillLayerJson.put("id", POLYGON_FILL_LAYER_ID);
            fillLayerJson.put("type", "fill");
            fillLayerJson.put("source", SOURCE_ID);
            fillLayerJson.put("paint", fillPaint);

            Expected<String, None> fillResult = style.addStyleLayer(
                    Objects.requireNonNull(Value.fromJson(fillLayerJson.toString()).getValue()), null);
            LOGGER.debug("exposure dataset polygon fill layer: " + (fillResult.isError() ? fillResult.getError() : "success"));
        }

        if (!style.styleLayerExists(POLYGON_OUTLINE_LAYER_ID)) {
            JSONObject outlinePaint = new JSONObject();
            outlinePaint.put("line-color", "#FF6B35");
            outlinePaint.put("line-width", 1.5);

            JSONObject outlineLayerJson = new JSONObject();
            outlineLayerJson.put("id", POLYGON_OUTLINE_LAYER_ID);
            outlineLayerJson.put("type", "line");
            outlineLayerJson.put("source", SOURCE_ID);
            outlineLayerJson.put("paint", outlinePaint);

            Expected<String, None> outlineResult = style.addStyleLayer(
                    Objects.requireNonNull(Value.fromJson(outlineLayerJson.toString()).getValue()), null);
            LOGGER.debug("exposure dataset polygon outline layer: " + (outlineResult.isError() ? outlineResult.getError() : "success"));
        }
    }

    /** Scans every feature's geometry.type for any of the given types (e.g. "Polygon", "MultiPolygon"). */
    private boolean containsGeometryType(JSONObject geoJsonObject, String... types) throws JSONException {
        JSONArray features = geoJsonObject.optJSONArray("features");
        if (features == null) return false;

        for (int i = 0; i < features.length(); i++) {
            JSONObject geometry = features.getJSONObject(i).optJSONObject("geometry");
            if (geometry == null) continue;
            String type = geometry.optString("type", "");
            for (String target : types) {
                if (target.equals(type)) return true;
            }
        }
        return false;
    }
}