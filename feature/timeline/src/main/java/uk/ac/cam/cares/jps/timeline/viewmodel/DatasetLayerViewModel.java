package uk.ac.cam.cares.jps.timeline.viewmodel;

import android.graphics.Bitmap;                          // NEW

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import uk.ac.cam.cares.jps.data.DatasetLayerRepository;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * Loads data for whichever dataset id it's asked for (the id saved in the exposure
 * calculation settings). Vector datasets are fetched as GeoJSON; raster datasets (NEW)
 * are fetched as a rendered image. Results are cached once fetched, since this is
 * static reference data.
 */
@HiltViewModel
public class DatasetLayerViewModel extends ViewModel {

    // NEW: layers whose GeoServer name ends in "_raster" (ndvi_raster, utci_raster) are rasters.
    // Naming convention instead of a type field, so no agent/KG change is needed.
    private static final String RASTER_SUFFIX = "_raster";

    private final DatasetLayerRepository datasetLayerRepository;

    private final Map<String, String> geoJsonCache = new HashMap<>();
    private final MutableLiveData<String> _datasetGeoJson = new MutableLiveData<>();
    private final MutableLiveData<String> _loadedDatasetId = new MutableLiveData<>();
    private final MutableLiveData<Throwable> _error = new MutableLiveData<>();

    // NEW: raster equivalents of the vector LiveData above
    private final MutableLiveData<Bitmap> _rasterBitmap = new MutableLiveData<>();
    private final MutableLiveData<String> _loadedRasterId = new MutableLiveData<>();

    public LiveData<String> datasetGeoJson = _datasetGeoJson;
    /** The dataset id that {@link #datasetGeoJson} currently corresponds to. */
    public LiveData<String> loadedDatasetId = _loadedDatasetId;
    public LiveData<Throwable> error = _error;

    /** NEW: the rendered raster image. */
    public LiveData<Bitmap> rasterBitmap = _rasterBitmap;
    /** NEW: the dataset id that {@link #rasterBitmap} currently corresponds to. */
    public LiveData<String> loadedRasterId = _loadedRasterId;

    private String pendingDatasetId;

    // NEW: only the most recent raster is cached. Each image is around 12 MB, so caching
    // every raster would use too much memory. Switching back to the same raster is still instant.
    private String cachedRasterId;
    private Bitmap cachedRaster;
    private String pendingRasterId;

    @Inject
    public DatasetLayerViewModel(DatasetLayerRepository datasetLayerRepository) {
        this.datasetLayerRepository = datasetLayerRepository;
    }

    /** NEW: true if this dataset id is a raster layer. DatasetLayerManager uses this to pick the draw path. */
    public static boolean isRasterDataset(String datasetId) {
        return datasetId != null && datasetId.toLowerCase().endsWith(RASTER_SUFFIX);
    }

    /** Vector datasets: fetch the GeoJSON, serving from cache if we already have it. Unchanged. */
    public void loadDataset(String datasetId) {
        if (datasetId == null || datasetId.isEmpty()) return;

        String cached = geoJsonCache.get(datasetId);
        if (cached != null) {
            _loadedDatasetId.postValue(datasetId);
            _datasetGeoJson.postValue(cached);
            return;
        }

        if (datasetId.equals(pendingDatasetId)) return; // already fetching this one
        pendingDatasetId = datasetId;

        datasetLayerRepository.getDatasetLayer(datasetId, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                pendingDatasetId = null;
                geoJsonCache.put(datasetId, result);
                _loadedDatasetId.postValue(datasetId);
                _datasetGeoJson.postValue(result);
            }

            @Override
            public void onFailure(Throwable error) {
                pendingDatasetId = null;
                _error.postValue(error);
            }
        });
    }

    /**
     * NEW: raster datasets. Fetch the rendered image for the given area, serving from the
     * one-entry cache if it is the same raster as last time.
     *
     * @param bbox {minLon, minLat, maxLon, maxLat} in WGS84 degrees
     */
    public void loadRaster(String datasetId, double[] bbox) {
        if (datasetId == null || datasetId.isEmpty()) return;

        if (datasetId.equals(cachedRasterId) && cachedRaster != null) {
            _loadedRasterId.postValue(datasetId);
            _rasterBitmap.postValue(cachedRaster);
            return;
        }

        if (datasetId.equals(pendingRasterId)) return; // already fetching this one
        pendingRasterId = datasetId;

        datasetLayerRepository.getRasterOverlay(datasetId, bbox, new RepositoryCallback<>() {
            @Override
            public void onSuccess(Bitmap result) {
                pendingRasterId = null;
                cachedRasterId = datasetId;   // replaces the previous raster, freeing its memory
                cachedRaster = result;
                _loadedRasterId.postValue(datasetId);
                _rasterBitmap.postValue(result);
            }

            @Override
            public void onFailure(Throwable error) {
                pendingRasterId = null;
                _error.postValue(error);
            }
        });
    }
}