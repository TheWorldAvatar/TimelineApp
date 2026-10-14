package uk.ac.cam.cares.jps.data;

import android.graphics.Bitmap;                          // NEW

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.DatasetLayerNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * Repository for an arbitrary exposure dataset layer, served via GeoServerJwtProxy.
 * The dataset id (as saved by the exposure calculation settings, and as returned by
 * {@link ExposureDatasetRepository}) is the bare table name. This repository adds
 * the "twa:" workspace prefix to build the typeName / layer name.
 */
public class DatasetLayerRepository {
    // "twa" is the GeoServer workspace. It is the same for WFS typeName and WMS layer name.
    private static final String WFS_NAMESPACE_PREFIX = "twa:";

    private final DatasetLayerNetworkSource datasetLayerNetworkSource;
    private final LoginRepository loginRepository;

    // Constructor unchanged, so your Hilt module does not need touching
    public DatasetLayerRepository(DatasetLayerNetworkSource datasetLayerNetworkSource,
                                  LoginRepository loginRepository) {
        this.datasetLayerNetworkSource = datasetLayerNetworkSource;
        this.loginRepository = loginRepository;
    }

    /** Vector datasets (point/polygon): unchanged. */
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

    /**
     * NEW: raster datasets (e.g. ndvi_raster, utci_raster). Same pattern as above:
     * get a fresh token, then ask the network source for the rendered image.
     *
     * @param bbox {minLon, minLat, maxLon, maxLat} in WGS84 degrees: the area to render
     */
    public void getRasterOverlay(String datasetId, double[] bbox, RepositoryCallback<Bitmap> callback) {
        String layerName = datasetId.contains(":") ? datasetId : WFS_NAMESPACE_PREFIX + datasetId;

        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                datasetLayerNetworkSource.getRasterImage(
                        accessToken,
                        layerName,
                        bbox,
                        callback::onSuccess,
                        error -> callback.onFailure(new Exception("Failed to get raster layer '" + layerName + "'", error))
                );
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(new Exception("Failed to obtain access token", error));
            }
        });
    }
}