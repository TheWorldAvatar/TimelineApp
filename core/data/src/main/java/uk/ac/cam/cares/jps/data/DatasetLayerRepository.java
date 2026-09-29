package uk.ac.cam.cares.jps.data;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.DatasetLayerNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * Repository for an arbitrary exposure dataset layer, served via GeoServerJwtProxy.
 * The dataset id (as saved by the exposure calculation settings, and as returned by
 * {@link ExposureDatasetRepository}) is the bare table name — this repository adds
 * the "twa:" WFS namespace prefix to build the typeName.
 */
public class DatasetLayerRepository {
    private static final String WFS_NAMESPACE_PREFIX = "twa:";

    private final DatasetLayerNetworkSource datasetLayerNetworkSource;
    private final LoginRepository loginRepository;

    public DatasetLayerRepository(DatasetLayerNetworkSource datasetLayerNetworkSource,
                                   LoginRepository loginRepository) {
        this.datasetLayerNetworkSource = datasetLayerNetworkSource;
        this.loginRepository = loginRepository;
    }

    public void getDatasetLayer(String datasetId, RepositoryCallback<String> callback) {
        String typeName = datasetId.contains(":") ? datasetId : WFS_NAMESPACE_PREFIX + datasetId;

        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                datasetLayerNetworkSource.getDatasetLayer(
                        accessToken,
                        typeName,
                        callback::onSuccess,
                        error -> callback.onFailure(new Exception("Failed to get dataset layer '" + typeName + "'", error))
                );
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(new Exception("Failed to obtain access token", error));
            }
        });
    }
}