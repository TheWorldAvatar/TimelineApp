package uk.ac.cam.cares.jps.data;

import java.util.List;
import uk.ac.cam.cares.jps.model.ExposureDataset;
import uk.ac.cam.cares.jps.network.BlazegraphNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class ExposureDatasetRepository {
    private final BlazegraphNetworkSource networkSource;

    public ExposureDatasetRepository(BlazegraphNetworkSource networkSource) {
        this.networkSource = networkSource;
    }

    public void getDatasets(RepositoryCallback<List<ExposureDataset>> callback) {
        networkSource.getDatasets(callback::onSuccess, callback::onFailure);
    }
}