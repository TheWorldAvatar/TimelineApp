package uk.ac.cam.cares.jps.sensor.data;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import uk.ac.cam.cares.jps.sensor.SensorService;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.sensor.source.worker.SensorUploadWorker;
import uk.ac.cam.cares.jps.sensor.source.worker.UnsentDataUploadWorker;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

public class SensorRepository {
    private static final String UNIQUE_SENSOR_UPLOAD_WORK = "pending_sensor_upload";
    private static final String UNIQUE_UNSENT_UPLOAD_WORK = "pending_unsent_data_upload";

    SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository;
    SensorLocalSource sensorLocalSource;
    Logger LOGGER = Logger.getLogger(SensorRepository.class);
    Context context;

    public SensorRepository(Context applicationContext,
                            SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository,
                            SensorLocalSource sensorLocalSource) {
        this.context = applicationContext;
        this.sensorCollectionStateManagerRepository = sensorCollectionStateManagerRepository;
        this.sensorLocalSource = sensorLocalSource;
        schedulePendingUploads();
    }

    private void schedulePendingUploads() {
        try {
            Constraints networkConstraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest sensorUpload = new OneTimeWorkRequest.Builder(SensorUploadWorker.class)
                    .addTag("dataUploadWork")
                    .setConstraints(networkConstraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();

            OneTimeWorkRequest unsentUpload = new OneTimeWorkRequest.Builder(UnsentDataUploadWorker.class)
                    .addTag("unsentDataUploadWorker")
                    .setConstraints(networkConstraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();

            WorkManager workManager = WorkManager.getInstance(context);
            workManager.enqueueUniqueWork(UNIQUE_SENSOR_UPLOAD_WORK, ExistingWorkPolicy.KEEP, sensorUpload);
            workManager.enqueueUniqueWork(UNIQUE_UNSENT_UPLOAD_WORK, ExistingWorkPolicy.KEEP, unsentUpload);
        } catch (IllegalStateException e) {
            LOGGER.warn("WorkManager not yet initialized when creating SensorRepository", e);
        }
    }

    public void startRecording(List<SensorType> selectedSensorTypes, RepositoryCallback<Boolean> callback) {
        LOGGER.info("start recording with selected sensors");
        final List<SensorType> sensorsToRecord = selectedSensors(selectedSensorTypes);
        final String newSessionId = UUID.randomUUID().toString();

        sensorCollectionStateManagerRepository.getHashedFileName(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String hashedFileName) {
                sensorLocalSource.initAppDataBase(hashedFileName);
                LOGGER.info("successfully init local database");

                sensorCollectionStateManagerRepository.beginNewSession(newSessionId, sensorsToRecord, new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(String deviceId) {
                        Intent intent = new Intent(context, SensorService.class);
                        intent.putExtra("deviceId", deviceId);
                        intent.putExtra("sessionId", newSessionId);
                        intent.putParcelableArrayListExtra("selectedSensors", new ArrayList<>(sensorsToRecord));
                        ContextCompat.startForegroundService(context, intent);
                        callback.onSuccess(true);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        callback.onFailure(error);
                    }
                });
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }

    private List<SensorType> selectedSensors(List<SensorType> selectedSensorTypes) {
        return selectedSensorTypes != null ? selectedSensorTypes : new ArrayList<>();
    }

    public void stopRecording() {
        LOGGER.info("stop recording");
        sensorCollectionStateManagerRepository.clearRecordingSession();
        context.stopService(new Intent(context, SensorService.class));
    }

    public boolean isTaskRunning(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        return isServiceRunning(SensorService.class);
    }

    public boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (SensorService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}