package uk.ac.cam.cares.jps.timeline.viewmodel;

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
 * Loads the GeoJSON for whichever dataset id it's asked for (the id saved in the
 * exposure calculation settings). Each dataset's GeoJSON is cached once fetched —
 * this is static reference data, no need to re-fetch every time it's toggled on.
 */
@HiltViewModel
public class DatasetLayerViewModel extends ViewModel {

    private final DatasetLayerRepository datasetLayerRepository;

    private final Map<String, String> geoJsonCache = new HashMap<>();
    private final MutableLiveData<String> _datasetGeoJson = new MutableLiveData<>();
    private final MutableLiveData<String> _loadedDatasetId = new MutableLiveData<>();
    private final MutableLiveData<Throwable> _error = new MutableLiveData<>();

    public LiveData<String> datasetGeoJson = _datasetGeoJson;
    /** The dataset id that {@link #datasetGeoJson} currently corresponds to. */
    public LiveData<String> loadedDatasetId = _loadedDatasetId;
    public LiveData<Throwable> error = _error;

    private String pendingDatasetId;

    @Inject
    public DatasetLayerViewModel(DatasetLayerRepository datasetLayerRepository) {
        this.datasetLayerRepository = datasetLayerRepository;
    }

    /** Fetch the given dataset's GeoJSON, serving from cache if we already have it. */
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
}