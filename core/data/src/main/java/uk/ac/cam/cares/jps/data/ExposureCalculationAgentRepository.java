package uk.ac.cam.cares.jps.data;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.ExposureCalculationAgentNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class ExposureCalculationAgentRepository {

    private static final String DEFAULT_RDF_PREFIX = "https://www.theworldavatar.com/kg/ontoexposure/";
    private static final String DEFAULT_RDF_TYPE = "TrajectoryCount";
    private static final String DEFAULT_DISTANCE = "400";
    private static final String DEFAULT_EXPOSURE_DATASET_IRI = "https://theworldavatar.io/kg/fb345d84-5d84-4d9b-b6c6-4d6c327d25e6"; // Hawker Centre

    private final ExposureCalculationAgentNetworkSource networkSource;
    private final AppPreferenceRepository appPreferenceRepository;
    private final LoginRepository loginRepository;

    public ExposureCalculationAgentRepository(ExposureCalculationAgentNetworkSource networkSource,
                                              AppPreferenceRepository appPreferenceRepository,
                                              LoginRepository loginRepository) {
        this.networkSource = networkSource;
        this.appPreferenceRepository = appPreferenceRepository;
        this.loginRepository = loginRepository;
    }

    public void triggerCalculation(Long lowerbound, Long upperbound, RepositoryCallback<String> callback) {
        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                appPreferenceRepository.getExposureDataset(new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(String datasetPref) {
                        String datasetIri = (datasetPref == null || datasetPref.isEmpty()) ? DEFAULT_EXPOSURE_DATASET_IRI : datasetPref;

                        appPreferenceRepository.getExposureCalcType(new RepositoryCallback<>() {
                            @Override
                            public void onSuccess(String calcTypePref) {
                                String rdfType = DEFAULT_RDF_PREFIX + ((calcTypePref == null || calcTypePref.isEmpty()) ? DEFAULT_RDF_TYPE : calcTypePref);

                                appPreferenceRepository.getExposureDistance(new RepositoryCallback<>() {
                                    @Override
                                    public void onSuccess(String distancePref) {
                                        String distance = (distancePref == null || distancePref.isEmpty()) ? DEFAULT_DISTANCE : distancePref;

                                        networkSource.triggerCalculation(accessToken, rdfType, distance, datasetIri, lowerbound, upperbound,
                                                callback::onSuccess,
                                                error -> callback.onFailure(new Exception("Failed to trigger exposure calculation", error)));
                                    }

                                    @Override
                                    public void onFailure(Throwable error) {
                                        networkSource.triggerCalculation(accessToken, rdfType, DEFAULT_DISTANCE, datasetIri, lowerbound, upperbound,
                                                callback::onSuccess,
                                                e -> callback.onFailure(new Exception("Failed to trigger exposure calculation", e)));
                                    }
                                });
                            }

                            @Override
                            public void onFailure(Throwable error) {
                                callback.onFailure(new Exception("Failed to load exposure calc type setting", error));
                            }
                        });
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        callback.onFailure(new Exception("Failed to load exposure dataset setting", error));
                    }
                });
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(new Exception("Failed to fetch access token", error));
            }
        });
    }
}