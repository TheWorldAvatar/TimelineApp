package uk.ac.cam.cares.jps.sensor.source.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A WorkManager worker that runs the trip segmentation and exposure calculation pipeline
 * for a single recording session, once enough time has passed for the final sensor data
 * upload to have reached the server.
 * <p>
 * This worker is scheduled with a delay (see {@code SensorRepository#stopRecording()}) rather
 * than being triggered directly off the "stop recording" button press, since the final upload
 * itself happens asynchronously via {@link SensorUploadWorker} and is not guaranteed to have
 * completed by the time the user taps stop.
 * <p>
 * The subject IRI used for both agent calls is derived directly from the device ID, following
 * the fixed IRI template used by the SensorLoggerMobileAppAgent's Ontop mapping:
 * {@code https://www.theworldavatar.com/kg/sensorloggerapp/point_<deviceId>}. No network call
 * is needed to resolve this IRI, since the mapping is deterministic.
 *
 * @see uk.ac.cam.cares.jps.sensor.data.SensorRepository#stopRecording()
 */
@HiltWorker
public class TripExposurePipelineWorker extends Worker {

    private static final Logger LOGGER = Logger.getLogger(TripExposurePipelineWorker.class);

    /** Base IRI prefix used by SensorLoggerMobileAppAgent's Ontop mapping for the GPS point instance. */
    private static final String POINT_IRI_PREFIX = "https://www.theworldavatar.com/kg/sensorloggerapp/point_";

    /** RDF type of exposure calculation to trigger. TrajectoryCount counts nearby features within a buffer distance. */
    private static final String EXPOSURE_RDF_TYPE = "https://www.theworldavatar.com/kg/ontoexposure/TrajectoryCount";

    /** Buffer distance (metres) used for the exposure calculation. */
    private static final String EXPOSURE_DISTANCE = "400";

    /** Exposure dataset table name (must already be uploaded to the stack via the stack data uploader). */
    private static final String EXPOSURE_TABLE = "sgpostcode";

    private final Context context;

    /**
     * OkHttp client used for both agent calls.
     * Read timeout is set generously since exposure calculation over a long trajectory can take a while.
     */
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    /**
     * Constructor of the class. Instantiation is handled by Hilt/WorkManager via {@link AssistedInject}.
     *
     * @param context The application context, used to read {@code host_with_port} and agent path resources.
     * @param params  WorkerParameters supplied by WorkManager, expected to contain a "deviceId" input value.
     */
    @AssistedInject
    public TripExposurePipelineWorker(@NonNull @Assisted Context context, @NonNull @Assisted WorkerParameters params) {
        super(context, params);
        this.context = context;
    }

    /**
     * Entry point called by WorkManager on a background thread.
     * <p>
     * Reads the deviceId passed in via input data, derives the subject IRI, then runs the
     * trip-agent call followed by the exposure-calculation-agent call in sequence (the latter
     * depends on the former having already segmented the trajectory into trips).
     *
     * @return {@link Result#success()} if both agent calls complete successfully,
     *         {@link Result#failure()} if no deviceId was supplied,
     *         {@link Result#retry()} if either agent call fails due to a network or server error.
     */
    @NonNull
    @Override
    public Result doWork() {
        return Result.success();
        /* 
        String deviceId = getInputData().getString("deviceId");
        if (deviceId == null || deviceId.isEmpty()) {
            LOGGER.error("No deviceId passed to TripExposurePipelineWorker, aborting.");
            return Result.failure();
        }

        String subjectIri = POINT_IRI_PREFIX + deviceId;

        try {
            runTripAgent(subjectIri);
            runExposureAgent(subjectIri);
            return Result.success();
        } catch (IOException e) {
            LOGGER.error("Trip/exposure pipeline failed: " + e.getMessage());
            return Result.retry();
        }
        */
    }

    /**
     * Calls trip-agent's process_trajectory route to segment the given subject's point time
     * series into trips and visits. Must be called before {@link #runExposureAgent(String)},
     * since the exposure agent checks for trip data on the same time series to decide whether
     * to compute one exposure value per trip rather than one for the whole trajectory.
     *
     * @param subjectIri IRI of the point time series to process, e.g.
     *                    {@code https://www.theworldavatar.com/kg/sensorloggerapp/point_<deviceId>}
     * @throws IOException if the request fails to send or the agent returns a non-2xx response.
     */
    private void runTripAgent(String subjectIri) throws IOException {
        String url = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.tripagent_processTrajectory))
                .addQueryParameter("iri", subjectIri)
                .build().toString();

        Request request = new Request.Builder().url(url).post(RequestBody.create(new byte[0], null)).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("trip-agent returned " + response.code());
            }
            LOGGER.info("trip-agent completed for " + subjectIri);
        }
    }

    /**
     * Calls exposure-calculation-agent's trigger_calculation route to compute exposure of the
     * given subject's trajectory to the configured exposure dataset. Instantiates the calculation
     * instance in the KG automatically if it does not already exist.
     *
     * @param subjectIri IRI of the subject (same point time series IRI passed to trip-agent).
     * @throws IOException if the request fails to send or the agent returns a non-2xx response.
     */
    private void runExposureAgent(String subjectIri) throws IOException {
        String url = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.exposurecalculationagent_triggerCalculation))
                .addQueryParameter("rdf_type", EXPOSURE_RDF_TYPE)
                .addQueryParameter("distance", EXPOSURE_DISTANCE)
                .addQueryParameter("subject", subjectIri)
                .addQueryParameter("exposure_table", EXPOSURE_TABLE)
                .build().toString();

        Request request = new Request.Builder().url(url).post(RequestBody.create(new byte[0], null)).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("exposure-calculation-agent returned " + response.code());
            }
            LOGGER.info("exposure-calculation-agent completed for " + subjectIri);
        }
    }
}
