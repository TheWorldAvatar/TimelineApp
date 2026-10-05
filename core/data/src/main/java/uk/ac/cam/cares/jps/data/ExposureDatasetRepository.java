package uk.ac.cam.cares.jps.data;

import java.util.List;
import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.model.ExposureDataset;
import uk.ac.cam.cares.jps.network.ExposureFeatureInfoNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class ExposureDatasetRepository {
    private final ExposureFeatureInfoNetworkSource networkSource;
    private final LoginRepository loginRepository;

    public ExposureDatasetRepository(ExposureFeatureInfoNetworkSource networkSource, LoginRepository loginRepository) {
        this.networkSource = networkSource;
        this.loginRepository = loginRepository;
    }

    public void getDatasets(RepositoryCallback<List<ExposureDataset>> callback) {
        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                networkSource.getDatasets(
                        accessToken,
                        "https://www.theworldavatar.com/kg/ontoexposure/ExposureDataset",
                        callback::onSuccess,
                        error -> callback.onFailure(new Throwable("Failed to get exposure datasets", error)));
            }
            @Override
            public void onFailure(Throwable error) { callback.onFailure(error); }
        });
    }
}