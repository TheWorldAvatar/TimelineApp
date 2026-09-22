package uk.ac.cam.cares.jps.data;

import uk.ac.cam.cares.jps.network.ExposureCalculationAgentNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class ExposureCalculationAgentRepository {

    /** Must match SensorLoggerMobileAppAgent's Ontop mapping — see TripExposurePipelineWorker. */
    private static final String POINT_IRI_PREFIX = "https://www.theworldavatar.com/kg/sensorloggerapp/point_";

    private static final String DEFAULT_RDF_PREFIX = "https://www.theworldavatar.com/kg/ontoexposure/";

    private static final String DEFAULT_RDF_TYPE = "TrajectoryCount";
    private static final String DEFAULT_DISTANCE = "400";
    private static final String DEFAULT_EXPOSURE_TABLE = "hawker_centre";

    private final ExposureCalculationAgentNetworkSource networkSource;
    private final AppPreferenceRepository appPreferenceRepository;

    public ExposureCalculationAgentRepository(ExposureCalculationAgentNetworkSource networkSource,
                                               AppPreferenceRepository appPreferenceRepository) {
        this.networkSource = networkSource;
        this.appPreferenceRepository = appPreferenceRepository;
    }

    /**
     * Triggers exposure calculation for the given device's trajectory, using whatever
     * dataset/calc-type/distance the user last saved in ExposureSettingFragment,
     * falling back to sensible defaults if nothing has been saved yet.
     */
    public void triggerCalculation(String deviceId, Long lowerbound, Long upperbound, RepositoryCallback<String> callback) {
        String subjectIri = POINT_IRI_PREFIX + deviceId;

        appPreferenceRepository.getExposureDataset(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String datasetPref) {
                String exposureTable = (datasetPref == null || datasetPref.isEmpty()) ? DEFAULT_EXPOSURE_TABLE : datasetPref;

                appPreferenceRepository.getExposureCalcType(new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(String calcTypePref) {
                        String rdfType =  DEFAULT_RDF_PREFIX + ((calcTypePref == null || calcTypePref.isEmpty()) ? DEFAULT_RDF_TYPE : calcTypePref);

                        appPreferenceRepository.getExposureDistance(new RepositoryCallback<>() {
                            @Override
                            public void onSuccess(String distancePref) {
                                String distance = (distancePref == null || distancePref.isEmpty()) ? DEFAULT_DISTANCE : distancePref;

                                networkSource.triggerCalculation(subjectIri, rdfType, distance, exposureTable, lowerbound, upperbound,
                                        callback::onSuccess,
                                        error -> callback.onFailure(new Exception("Failed to trigger exposure calculation", error)));
                            }

                            @Override
                            public void onFailure(Throwable error) {
                                networkSource.triggerCalculation(subjectIri, rdfType, DEFAULT_DISTANCE, exposureTable, lowerbound, upperbound,
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
}