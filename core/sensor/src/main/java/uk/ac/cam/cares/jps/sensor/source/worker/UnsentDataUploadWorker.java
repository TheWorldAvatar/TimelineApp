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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.UnsentData;
import uk.ac.cam.cares.jps.sensor.source.network.SensorNetworkSource;
import uk.ac.cam.cares.jps.login.LoginRepository;

@HiltWorker
public class UnsentDataUploadWorker extends Worker {
    private final SensorLocalSource sensorLocalSource;
    private final SensorNetworkSource sensorNetworkSource;
    private final Logger LOGGER = Logger.getLogger(UnsentDataUploadWorker.class);

    private final String taskId;

    private final LoginRepository loginRepository;

    @AssistedInject
    public UnsentDataUploadWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams,
                                SensorLocalSource sensorLocalSource,
                                SensorNetworkSource sensorNetworkSource,
                                LoginRepository loginRepository) {
        super(context, workerParams);
        this.sensorLocalSource = sensorLocalSource;
        this.sensorNetworkSource = sensorNetworkSource;
        this.loginRepository = loginRepository;
        this.taskId = workerParams.getInputData().getString("taskId");
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            uploadUnsentData();
            return Result.success();
        } catch (Exception e) {
            Log.e("DataUploadWorker", "Error uploading sensor data", e);
            return Result.failure();
        }
    }

    private void uploadUnsentData() throws IOException {
        int limit = 100;

        while (true) {
            // Always read from offset 0: rows we've confirmed sent are deleted
            // before the next read, so whatever is left has already "slid down".
            // Advancing the offset here (like the old code did) while also
            // deleting the same rows caused every other page to be skipped.
            List<UnsentData> unsentDataList = sensorLocalSource.retrieveUnsentData(limit, 0);
            if (unsentDataList.isEmpty()) {
                break;
            }

            Map<String, List<UnsentData>> byDevice = groupByDevice(unsentDataList);
            boolean anySucceeded = false;

            for (Map.Entry<String, List<UnsentData>> entry : byDevice.entrySet()) {
                String deviceId = entry.getKey();
                List<UnsentData> deviceRows = entry.getValue();
                String payload = combinePayload(deviceRows);

                try {
                    byte[] compressedData = compressData(payload);
                    String accessToken = fetchAccessTokenBlocking();
                    // We now know, on this line, whether the server actually got it.
                    boolean success = sensorNetworkSource.sendPostRequestSync(deviceId, taskId, compressedData, payload, accessToken);

                    if (success) {
                        // Only delete once delivery is confirmed.
                        sensorLocalSource.deleteUnsentData(deviceRows);
                        anySucceeded = true;
                        LOGGER.info("Unsent data upload confirmed for device " + deviceId);
                    } else {
                        LOGGER.warn("Unsent data upload failed for device " + deviceId + ", leaving rows for next retry");
                    }
                } catch (Exception e) {
                    LOGGER.error("Error processing unsent data for device " + deviceId, e);
                }
            }

            if (!anySucceeded) {
                // Still offline / server unreachable - stop instead of spinning
                // forever on the same rows. Next connectivity change tries again.
                break;
            }
        }
    }

    private Map<String, List<UnsentData>> groupByDevice(List<UnsentData> unsentDataList) {
        Map<String, List<UnsentData>> byDevice = new HashMap<>();
        for (UnsentData data : unsentDataList) {
            byDevice.computeIfAbsent(data.deviceId, k -> new ArrayList<>()).add(data);
        }
        return byDevice;
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

    private String fetchAccessTokenBlocking() throws IOException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> tokenRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();

        loginRepository.getAccessToken(new uk.ac.cam.cares.jps.utils.RepositoryCallback<String>() {
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
