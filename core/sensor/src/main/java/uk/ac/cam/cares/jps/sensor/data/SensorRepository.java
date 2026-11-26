package uk.ac.cam.cares.jps.sensor.data;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import uk.ac.cam.cares.jps.sensor.SensorService;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.sensor.source.worker.SensorUploadWorker;
import uk.ac.cam.cares.jps.sensor.source.worker.UnsentDataUploadWorker;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;


/**
 * A repository level component that provides control of sensor collection to UI level component
 * Functionalities:
 * 1. Start/stop recording in service
 * @see SensorService SensorService
 */
public class SensorRepository {
    private static final String UNIQUE_SENSOR_UPLOAD_WORK = "pending_sensor_upload";
    private static final String UNIQUE_UNSENT_UPLOAD_WORK = "pending_unsent_data_upload";

    SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository;
    SensorLocalSource sensorLocalSource;
    Logger LOGGER = Logger.getLogger(SensorRepository.class);
    Intent serviceIntent;
    Context context;

    /**
     * Constructor of the class. The instantiation is handled by dependency injection.
     * @param sensorCollectionStateManagerRepository SensorCollectionStateManagerRepository object
     */
    public SensorRepository(Context applicationContext,
                            SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository,
                            SensorLocalSource sensorLocalSource) {
        this.context = applicationContext;
        this.sensorCollectionStateManagerRepository = sensorCollectionStateManagerRepository;
        this.sensorLocalSource = sensorLocalSource;

        serviceIntent = new Intent(context, SensorService.class);
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

    /**
     * Start foreground task to record sensor data
     * @param callback callback
     */
    public void startRecording(List<SensorType> selectedSensorTypes, RepositoryCallback<Boolean> callback) {
        LOGGER.info("start recording with selected sensors");
        sensorCollectionStateManagerRepository.setSelectedSensors(selectedSensorTypes);
        prepareAndStartCollection(selectedSensorTypes, callback);
    }

    private void prepareAndStartCollection(List<SensorType> selectedSensorTypes, RepositoryCallback<Boolean> callback) {
        sensorCollectionStateManagerRepository.getHashedFileName(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String hashedFileName) {
                sensorLocalSource.initAppDataBase(hashedFileName);
                LOGGER.info("successfully init local database");

                startSensorCollection(selectedSensorTypes, callback);
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }

    private void startSensorCollection(List<SensorType> selectedSensorTypes, RepositoryCallback<Boolean> callback) {
        sensorCollectionStateManagerRepository.getDeviceId(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                serviceIntent.putExtra("deviceId", result);
                serviceIntent.putParcelableArrayListExtra("selectedSensors", new ArrayList<>(selectedSensors(selectedSensorTypes)));
                context.startService(serviceIntent);
                sensorCollectionStateManagerRepository.setRecordingState(true);
                callback.onSuccess(true);
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

        context.stopService(serviceIntent);
        sensorCollectionStateManagerRepository.setRecordingState(false);
        sensorCollectionStateManagerRepository.setTaskId("");
        // keep the saved sensor selection so the user's toggles persist after stopping
    }


    /**
     * Checks if a task with the given task ID is currently running.
     *
     * @param taskId  The unique identifier for the task that needs to be checked.
     * @return `true` if the task with the given ID is running, `false` otherwise.
     */
    public boolean isTaskRunning(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        return isServiceRunning(SensorService.class);
    }


    /**
     * Checks if a specified service is currently running in the background.
     *
     * @param serviceClass The class of the service that needs to be checked.
     * @return `true` if the specified service is currently running, `false` otherwise.
     */
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