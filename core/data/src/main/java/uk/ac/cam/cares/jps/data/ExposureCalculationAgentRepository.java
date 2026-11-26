package uk.ac.cam.cares.jps.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.ExposureCalculationAgentNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class ExposureCalculationAgentRepository {

    private static final String DEFAULT_RDF_PREFIX = "https://www.theworldavatar.com/kg/ontoexposure/";
    private static final String DEFAULT_RDF_TYPE = "TrajectoryCount";
    private static final String DEFAULT_DISTANCE = "400";
    private static final String DEFAULT_EXPOSURE_DATASET_IRI = "https://theworldavatar.io/kg/952db046-2282-4af1-a351-be715b6d8c95"; // Hawker Centre

    private final ExposureCalculationAgentNetworkSource networkSource;
    private final AppPreferenceRepository appPreferenceRepository;
    private final LoginRepository loginRepository;
    private final Context context;

    public ExposureCalculationAgentRepository(ExposureCalculationAgentNetworkSource networkSource,
                                              AppPreferenceRepository appPreferenceRepository,
                                              LoginRepository loginRepository,
                                              Context context) {
        this.networkSource = networkSource;
        this.appPreferenceRepository = appPreferenceRepository;
        this.loginRepository = loginRepository;
        this.context = context.getApplicationContext();
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
                                                queuedResponse -> awaitJob(accessToken, queuedResponse, lowerbound, upperbound, callback),
                                                error -> callback.onFailure(new Exception("Failed to trigger exposure calculation", error)));
                                    }

                                    @Override
                                    public void onFailure(Throwable error) {
                                        networkSource.triggerCalculation(accessToken, rdfType, DEFAULT_DISTANCE, datasetIri, lowerbound, upperbound,
                                                queuedResponse -> awaitJob(accessToken, queuedResponse, lowerbound, upperbound, callback),
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

    /** The agent answers 202 once the job is queued; report success only after the job has finished. */
    private void awaitJob(String accessToken, String queuedResponse,
                          Long lowerbound, Long upperbound,
                          RepositoryCallback<String> callback) {
        JobPoller.awaitCompletion(queuedResponse,
                fetchCallback -> networkSource.getActiveJobs(accessToken,
                        fetchCallback::onSuccess,
                        e -> fetchCallback.onFailure(e)),
                new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(Void unused) {
                        invalidateCacheForRange(lowerbound, upperbound);
                        callback.onSuccess(queuedResponse);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        callback.onFailure(new Exception("Failed while waiting for exposure calculation", error));
                    }
                });
    }

    private void invalidateCacheForRange(Long lowerbound, Long upperbound) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate startDate = lowerbound != null
                ? Instant.ofEpochMilli(lowerbound).atZone(zone).toLocalDate()
                : LocalDate.now(zone);
        LocalDate endDate = (upperbound != null && upperbound > 0)
                ? Instant.ofEpochMilli(Math.max(lowerbound != null ? lowerbound : upperbound, upperbound - 1))
                        .atZone(zone).toLocalDate()
                : startDate;

        SharedPreferences overviewPrefs = context.getSharedPreferences("exposure_overview_cache", Context.MODE_PRIVATE);
        SharedPreferences comparePrefs = context.getSharedPreferences("compare_trips_cache", Context.MODE_PRIVATE);
        SharedPreferences trajPrefs = context.getSharedPreferences("compare_trips_traj_cache", Context.MODE_PRIVATE);

        SharedPreferences.Editor overviewEd = overviewPrefs.edit();
        SharedPreferences.Editor compareEd = comparePrefs.edit();
        SharedPreferences.Editor trajEd = trajPrefs.edit();

        int guard = 0;
        for (LocalDate d = startDate; !d.isAfter(endDate) && guard < 31; d = d.plusDays(1), guard++) {
            String key = d.toString();
            overviewEd.remove(key);
            compareEd.remove(key);
            trajEd.remove("day|" + key);
        }

        long now = System.currentTimeMillis();
        overviewEd.putLong("__last_calc_ms", now).apply();
        compareEd.putLong("__last_calc_ms", now).apply();
        trajEd.apply();
    }
}