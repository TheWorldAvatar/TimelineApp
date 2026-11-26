package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.graphics.Bitmap;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.mapbox.bindgen.Expected;
import com.mapbox.bindgen.None;
import com.mapbox.bindgen.Value;
import com.mapbox.maps.ExtensionUtils;
import com.mapbox.maps.LayerPosition;
import com.mapbox.maps.MapView;
import com.mapbox.maps.Style;
import com.mapbox.maps.StyleObjectInfo;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import uk.ac.cam.cares.jps.timeline.viewmodel.DatasetLayerViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.AppPreferenceViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.DisplayOptionsViewModel;

/**
 * UI manager for the exposure dataset layer shown on the map. Which dataset is
 * displayed is driven by whatever dataset id is currently saved in the exposure
 * calculation settings, and visibility is toggled via Advanced Options (DisplayOptionsViewModel).
 */
public class DatasetLayerManager {
    private static final String SOURCE_ID = "exposure_dataset_source";
    private static final String POINT_LAYER_ID = "exposure_dataset_point_layer";
    private static final String POLYGON_FILL_LAYER_ID = "exposure_dataset_polygon_fill_layer";
    private static final String POLYGON_OUTLINE_LAYER_ID = "exposure_dataset_polygon_outline_layer";
    private static final String RASTER_SOURCE_ID = "exposure_dataset_raster_source";
    private static final String RASTER_LAYER_ID = "exposure_dataset_raster_layer";

    private static final double MIN_LON = 103.60;
    private static final double MIN_LAT = 1.15;
    private static final double MAX_LON = 104.05;
    private static final double MAX_LAT = 1.48;
    private static final double[] RASTER_BBOX = {MIN_LON, MIN_LAT, MAX_LON, MAX_LAT};

    private final Logger LOGGER = Logger.getLogger(DatasetLayerManager.class);
    private final Fragment fragment;
    private final DatasetLayerViewModel viewModel;
    private final AppPreferenceViewModel appPreferenceViewModel;
    private final DisplayOptionsViewModel displayOptionsViewModel;
    private final MapView mapView;
    private String selectedDatasetId;
    private String paintedDatasetId;
    private boolean isVisible = false;

    public DatasetLayerManager(Fragment fragment, MapView mapView) {
        this.fragment = fragment;
        this.mapView = mapView;
        viewModel = new ViewModelProvider(fragment).get(DatasetLayerViewModel.class);
        appPreferenceViewModel = new ViewModelProvider(fragment.requireActivity()).get(AppPreferenceViewModel.class);
        displayOptionsViewModel = new ViewModelProvider(fragment.requireActivity()).get(DisplayOptionsViewModel.class);

        appPreferenceViewModel.loadExposureParams();
        appPreferenceViewModel.getExposureDatasetTableName().observe(fragment.getViewLifecycleOwner(), datasetId -> {
            selectedDatasetId = (datasetId == null || datasetId.isEmpty()) ? null : datasetId;
            syncVisibility();
        });

        displayOptionsViewModel.showDatasetLayer.observe(fragment.getViewLifecycleOwner(), show ->
                syncVisibility());

        viewModel.datasetGeoJson.observe(fragment.getViewLifecycleOwner(), geoJson -> {
            String loadedId = viewModel.loadedDatasetId.getValue();
            if (geoJson == null || geoJson.isEmpty() || loadedId == null) return;
            if (!loadedId.equals(selectedDatasetId)) return;
            if (isVisible) {
                mapView.getMapboxMap().getStyle(style -> paintDataset(style, loadedId, geoJson));
            }
        });

        viewModel.rasterBitmap.observe(fragment.getViewLifecycleOwner(), bitmap -> {
            String loadedId = viewModel.loadedRasterId.getValue();
            if (bitmap == null || loadedId == null) return;
            if (!loadedId.equals(selectedDatasetId)) return;
            if (isVisible) {
                mapView.getMapboxMap().getStyle(style -> paintRaster(style, loadedId, bitmap));
            }
        });

        viewModel.error.observe(fragment.getViewLifecycleOwner(), error -> {
            if (error != null) {
                LOGGER.error("Failed to load exposure dataset layer", error);
            }
        });
    }

    public void toggleDatasetLayer() {
        displayOptionsViewModel.setShowDatasetLayer(!displayOptionsViewModel.isShowDatasetLayer());
    }

    private void syncVisibility() {
        boolean shouldShow = displayOptionsViewModel.isShowDatasetLayer();
        if (!shouldShow) {
            if (isVisible) {
                isVisible = false;
                removeDatasetLayers();
            }
            return;
        }
        if (selectedDatasetId == null) {
            return;
        }
        if (!isVisible || !selectedDatasetId.equals(paintedDatasetId)) {
            isVisible = true;
            loadSelectedDataset();
        }
    }

    private void loadSelectedDataset() {
        if (DatasetLayerViewModel.isRasterDataset(selectedDatasetId)) {
            viewModel.loadRaster(selectedDatasetId, RASTER_BBOX);
        } else {
            viewModel.loadDataset(selectedDatasetId);
        }
    }

    private void removeDatasetLayers() {
        paintedDatasetId = null;
        mapView.getMapboxMap().getStyle(this::removeDatasetLayersAndSource);
    }

    private void paintRaster(Style style, String datasetId, Bitmap bitmap) {
        try {
            if (!datasetId.equals(paintedDatasetId)) {
                removeDatasetLayersAndSource(style);
            }
            paintedDatasetId = datasetId;

            if (!style.styleSourceExists(RASTER_SOURCE_ID)) {
                JSONArray coordinates = new JSONArray()
                        .put(new JSONArray().put(MIN_LON).put(MAX_LAT))
                        .put(new JSONArray().put(MAX_LON).put(MAX_LAT))
                        .put(new JSONArray().put(MAX_LON).put(MIN_LAT))
                        .put(new JSONArray().put(MIN_LON).put(MIN_LAT));
                JSONObject sourceJson = new JSONObject();
                sourceJson.put("type", "image");
                sourceJson.put("coordinates", coordinates);

                Expected<String, None> sourceResult = style.addStyleSource(
                        RASTER_SOURCE_ID,
                        Objects.requireNonNull(Value.fromJson(sourceJson.toString()).getValue())
                );
                LOGGER.debug("exposure dataset raster source: " + (sourceResult.isError() ? sourceResult.getError() : "success"));

                Expected<String, None> imageResult = style.updateStyleImageSourceImage(
                        RASTER_SOURCE_ID, ExtensionUtils.toMapboxImage(bitmap));
                LOGGER.debug("exposure dataset raster image: " + (imageResult.isError() ? imageResult.getError() : "success"));
            }

            addRasterLayer(style);
        } catch (JSONException e) {
            LOGGER.error("Error painting exposure raster layer", e);
        }
    }

    private void addRasterLayer(Style style) throws JSONException {
        if (style.styleLayerExists(RASTER_LAYER_ID)) return;
        JSONObject paint = new JSONObject();
        paint.put("raster-opacity", 0.7);
        paint.put("raster-fade-duration", 0);
        JSONObject layerJson = new JSONObject();
        layerJson.put("id", RASTER_LAYER_ID);
        layerJson.put("type", "raster");
        layerJson.put("source", RASTER_SOURCE_ID);
        layerJson.put("paint", paint);
        Expected<String, None> layerResult = style.addStyleLayer(
                Objects.requireNonNull(Value.fromJson(layerJson.toString()).getValue()),
                findPositionBelowLabels(style));
        LOGGER.debug("exposure dataset raster layer: " + (layerResult.isError() ? layerResult.getError() : "success"));
    }

    private LayerPosition findPositionBelowLabels(Style style) {
        for (StyleObjectInfo layerInfo : style.getStyleLayers()) {
            if ("symbol".equals(layerInfo.getType())) {
                return new LayerPosition(null, layerInfo.getId(), null);
            }
        }
        return null;
    }

    private void paintDataset(Style style, String datasetId, String geoJson) {
        try {
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
        if (style.styleLayerExists(RASTER_LAYER_ID)) style.removeStyleLayer(RASTER_LAYER_ID);
        if (style.styleSourceExists(RASTER_SOURCE_ID)) style.removeStyleSource(RASTER_SOURCE_ID);
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