package uk.ac.cam.cares.jps.sensor.source.worker;

import static uk.ac.cam.cares.jps.utils.di.UtilsModule.compressData;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.UnsentData;
import uk.ac.cam.cares.jps.sensor.source.network.SensorNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltWorker
public class UnsentDataUploadWorker extends Worker {
    private final SensorLocalSource sensorLocalSource;
    private final SensorNetworkSource sensorNetworkSource;
    private final SensorCollectionStateManagerRepository stateManagerRepository;
    private final Logger LOGGER = Logger.getLogger(UnsentDataUploadWorker.class);

    private String fallbackTaskId;

    private final LoginRepository loginRepository;

    @AssistedInject
    public UnsentDataUploadWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams,
                                  SensorLocalSource sensorLocalSource,
                                  SensorNetworkSource sensorNetworkSource,
                                  SensorCollectionStateManagerRepository stateManagerRepository,
                                  LoginRepository loginRepository) {
        super(context, workerParams);
        this.sensorLocalSource = sensorLocalSource;
        this.sensorNetworkSource = sensorNetworkSource;
        this.stateManagerRepository = stateManagerRepository;
        this.loginRepository = loginRepository;
        this.fallbackTaskId = workerParams.getInputData().getString("taskId");
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (!ensureDatabaseInitialized()) {
                return Result.success();
            }
            if (fallbackTaskId == null || fallbackTaskId.isEmpty()) {
                fallbackTaskId = fetchStateValueBlocking(stateManagerRepository::getTaskId);
            }

            boolean allUploaded = uploadUnsentData();
            return allUploaded ? Result.success() : Result.retry();
        } catch (IOException e) {
            LOGGER.warn("Transient error uploading unsent sensor data, scheduling retry", e);
            return Result.retry();
        } catch (Exception e) {
            Log.e("DataUploadWorker", "Error uploading sensor data", e);
            return Result.retry();
        }
    }

    private boolean uploadUnsentData() throws IOException {
        int limit = 100;
        boolean allUploaded = true;

        while (true) {
            List<UnsentData> unsentDataList = sensorLocalSource.retrieveUnsentData(limit, 0);
            if (unsentDataList.isEmpty()) {
                break;
            }

            // Never mix unsent batches across devices or recording sessions
            Map<String, List<UnsentData>> byDeviceAndSession = groupByDeviceAndSession(unsentDataList);
            boolean anySucceeded = false;

            for (List<UnsentData> batchRows : byDeviceAndSession.values()) {
                UnsentData first = batchRows.get(0);
                String deviceId = first.deviceId;
                String sessionId = (first.sessionId != null && !first.sessionId.isEmpty())
                        ? first.sessionId
                        : fallbackTaskId;
                String payload = combinePayload(batchRows);

                try {
                    byte[] compressedData = compressData(payload);
                    String accessToken = fetchAccessTokenBlocking();
                    boolean success = sensorNetworkSource.sendPostRequestSync(
                            deviceId, sessionId, compressedData, payload, accessToken);

                    if (success) {
                        sensorLocalSource.deleteUnsentData(batchRows);
                        anySucceeded = true;
                        LOGGER.info("Unsent data upload confirmed for device " + deviceId + ", session " + sessionId);
                    } else {
                        allUploaded = false;
                        LOGGER.warn("Unsent data upload failed for device " + deviceId + ", session " + sessionId + ", leaving rows for retry");
                    }
                } catch (Exception e) {
                    allUploaded = false;
                    LOGGER.error("Error processing unsent data for device " + deviceId + ", session " + sessionId, e);
                }
            }

            if (!anySucceeded) {
                allUploaded = false;
                break;
            }
        }
        return allUploaded;
    }

    private Map<String, List<UnsentData>> groupByDeviceAndSession(List<UnsentData> unsentDataList) {
        Map<String, List<UnsentData>> grouped = new LinkedHashMap<>();
        for (UnsentData data : unsentDataList) {
            String rowSession = data.sessionId != null ? data.sessionId : "";
            String key = data.deviceId + "|" + rowSession;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(data);
        }
        return grouped;
    }

    private String combinePayload(List<UnsentData> deviceRows) {
        StringBuilder combined = new StringBuilder();
        for (UnsentData data : deviceRows) {
            if (combined.length() > 0) {
                combined.append(",");
            }
            combined.append(data.data, 1, data.data.length() - 1); // strip stored [ ]
        }
        return "[" + combined + "]";
    }

    private boolean ensureDatabaseInitialized() {
        if (sensorLocalSource.isDatabaseInitialized()) {
            return true;
        }
        String hashedFileName = fetchStateValueBlocking(stateManagerRepository::getHashedFileName);
        if (hashedFileName == null || hashedFileName.isEmpty()) {
            LOGGER.info("Skipping UnsentDataUploadWorker: no active user database available.");
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