package uk.ac.cam.cares.jps.data;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.ExposureFeatureInfoNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class ExposureFeatureInfoRepository {
    private final ExposureFeatureInfoNetworkSource networkSource;
    private final LoginRepository loginRepository;

    public ExposureFeatureInfoRepository(ExposureFeatureInfoNetworkSource networkSource, LoginRepository loginRepository) {
        this.networkSource = networkSource;
        this.loginRepository = loginRepository;
    }

    public void getTimelineResults(long lowerbound, long upperbound, RepositoryCallback<String> callback) {
        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                networkSource.getTimelineResults(accessToken, lowerbound, upperbound,
                        callback::onSuccess,
                        error -> callback.onFailure(new Throwable("Failed to get exposure timeline results", error)));
            }
            @Override
            public void onFailure(Throwable error) { callback.onFailure(error); }
        });
    }
}