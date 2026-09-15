package uk.ac.cam.cares.jps.data;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.HawkerCentreNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * Repository for the static hawker centre point layer, served via GeoServerJwtProxy.
 */
public class HawkerCentreRepository {
    private final HawkerCentreNetworkSource hawkerCentreNetworkSource;
    private final LoginRepository loginRepository;

    public HawkerCentreRepository(HawkerCentreNetworkSource hawkerCentreNetworkSource,
                                   LoginRepository loginRepository) {
        this.hawkerCentreNetworkSource = hawkerCentreNetworkSource;
        this.loginRepository = loginRepository;
    }

    public void getHawkerCentres(RepositoryCallback<String> callback) {
        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                hawkerCentreNetworkSource.getHawkerCentres(
                        accessToken,
                        callback::onSuccess,
                        error -> callback.onFailure(new Exception("Failed to get hawker centres", error))
                );
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(new Exception("Failed to obtain access token", error));
            }
        });
    }
}
