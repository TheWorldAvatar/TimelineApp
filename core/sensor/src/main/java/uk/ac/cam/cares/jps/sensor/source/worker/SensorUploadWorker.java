package uk.ac.cam.cares.jps.sensor.source.worker;

import static uk.ac.cam.cares.jps.utils.Utils.compressData;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.apache.log4j.Logger;
import org.json.JSONArray;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.sensor.data.SensorCollectionStateManagerRepository;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.sensor.source.network.SensorNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltWorker
public class SensorUploadWorker extends Worker {

    private final SensorNetworkSource sensorNetworkSource;
    private final SensorLocalSource sensorLocalSource;
    private final SensorCollectionStateManagerRepository stateManagerRepository;
    private final Logger LOGGER = Logger.getLogger(SensorUploadWorker.class);
    private String deviceId;
    private String fallbackSessionId;
    private final List<SensorType> sensorsToUpload;
    private final LoginRepository loginRepository;

    @AssistedInject
    public SensorUploadWorker(@NonNull @Assisted Context context, @NonNull @Assisted WorkerParameters workerParams,
                              SensorNetworkSource sensorNetworkSource,
                              SensorLocalSource sensorLocalSource,
                              SensorCollectionStateManagerRepository stateManagerRepository,
                              LoginRepository loginRepository) {
        super(context, workerParams);
        this.sensorNetworkSource = sensorNetworkSource;
        this.sensorLocalSource = sensorLocalSource;
        this.stateManagerRepository = stateManagerRepository;
        this.loginRepository = loginRepository;
        this.deviceId = workerParams.getInputData().getString("deviceId");
        this.fallbackSessionId = workerParams.getInputData().getString("sessionId");
        // Always drain all sensor tables (grouped separately per sessionId) so pending
        // rows from an earlier recording with a different sensor set are never stranded.
        this.sensorsToUpload = Arrays.asList(SensorType.values());
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (!ensureDatabaseInitialized()) {
                return Result.success();
            }
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = fetchStateValueBlocking(stateManagerRepository::getDeviceId);
            }
            if (fallbackSessionId == null || fallbackSessionId.isEmpty()) {
                fallbackSessionId = fetchStateValueBlocking(stateManagerRepository::getTaskId);
            }
            if (deviceId == null || deviceId.isEmpty()) {
                return Result.retry();
            }

            boolean allUploaded = uploadSensorData();
            return allUploaded ? Result.success() : Result.retry();
        } catch (IOException e) {
            LOGGER.warn("Transient error uploading sensor data, scheduling retry", e);
            return Result.retry();
        } catch (Exception e) {
            Log.e("DataUploadWorker", "Error uploading sensor data", e);
            return Result.retry();
        }
    }

    private boolean uploadSensorData() throws IOException {
        int PAGE_SIZE = 800;
        boolean hasMoreData = true;
        boolean allUploaded = true;

        while (hasMoreData) {
            Map<String, Map<SensorType, List<Long>>> timesBySessionAndSensor = new HashMap<>();
            boolean[] anySensorHitLimit = new boolean[]{false};

            Map<String, JSONArray> batchesBySession = sensorLocalSource.retrieveUnUploadedSensorDataBySession(
                    sensorsToUpload, PAGE_SIZE, 0, timesBySessionAndSensor, anySensorHitLimit);

            if (batchesBySession.isEmpty()) {
                break;
            }

            boolean batchAllSucceeded = true;
            for (Map.Entry<String, JSONArray> entry : batchesBySession.entrySet()) {
                String rowSessionId = entry.getKey();
                JSONArray sensorData = entry.getValue();
                if (sensorData.length() == 0) {
                    continue;
                }

                String effectiveSessionId = !rowSessionId.isEmpty() ? rowSessionId : fallbackSessionId;
                byte[] compressedData = compressData(sensorData.toString());
                String accessToken = fetchAccessTokenBlocking();

                LOGGER.info("Attempting to send " + sensorData.length() + " items for session " + effectiveSessionId);
                boolean success = sensorNetworkSource.sendPostRequestSync(
                        deviceId, effectiveSessionId, compressedData, sensorData, accessToken);

                if (success) {
                    sensorLocalSource.markSensorDataAsUploaded(timesBySessionAndSensor.get(rowSessionId));
                    LOGGER.info("Confirmed & marked uploaded for session " + effectiveSessionId);
                } else {
                    LOGGER.warn("Upload failed for session " + effectiveSessionId + " — leaving unmarked for retry.");
                    batchAllSucceeded = false;
                    allUploaded = false;
                }
            }

            if (!batchAllSucceeded || !anySensorHitLimit[0]) {
                hasMoreData = false;
            }
        }
        return allUploaded;
    }

    private boolean ensureDatabaseInitialized() {
        if (sensorLocalSource.isDatabaseInitialized()) {
            return true;
        }
        String hashedFileName = fetchStateValueBlocking(stateManagerRepository::getHashedFileName);
        if (hashedFileName == null || hashedFileName.isEmpty()) {
            LOGGER.info("Skipping SensorUploadWorker: no active user database available.");
            return false;
        }
        sensorLocalSource.initAppDataBase(hashedFileName);
        return true;
    }

    private String fetchStateValueBlocking(Consumer<RepositoryCallback<String>> loader) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> valueRef = new AtomicReference<>();

        loader.accept(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                valueRef.set(result);
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable error) {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return valueRef.get();
    }

    private String fetchAccessTokenBlocking() throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> tokenRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        loginRepository.getAccessToken(new RepositoryCallback<String>() {
            @Override
            public void onSuccess(String token) {
                tokenRef.set(token);
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for access token", e);
        }

        if (errorRef.get() != null) {
            throw new IOException("Failed to fetch access token", errorRef.get());
        }

        LOGGER.info("Using access token ending in: " + tokenRef.get().substring(Math.max(0, tokenRef.get().length() - 10)));
        return tokenRef.get();
    }
}