package uk.ac.cam.cares.jps.data;

import android.content.Context;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.TripAgentNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class TripAgentRepository {
    private final TripAgentNetworkSource tripAgentNetworkSource;
    private final LoginRepository loginRepository;
    private final Context context;

    public TripAgentRepository(TripAgentNetworkSource tripAgentNetworkSource,
                                LoginRepository loginRepository,
                                Context context) {
        this.tripAgentNetworkSource = tripAgentNetworkSource;
        this.loginRepository = loginRepository;
        this.context = context;
    }

    public void runTripAgent(Long lowerbound, Long upperbound, RepositoryCallback<String> callback) {
        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                tripAgentNetworkSource.processTrajectoryForTimeline(accessToken, lowerbound, upperbound,
                        callback::onSuccess,
                        error -> callback.onFailure(new Throwable("Failed to run trip agent", error)));
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }
}