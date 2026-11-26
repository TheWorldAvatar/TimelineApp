package uk.ac.cam.cares.jps.sensor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.sensor.data.SensorCollectionStateManagerRepository;
import uk.ac.cam.cares.jps.sensor.source.activity.SensorActivityClassifier;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.handler.LocationHandler;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorHandlerManager;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.sensor.source.network.NetworkChangeReceiver;
import uk.ac.cam.cares.jps.sensor.source.network.SensorNetworkSource;
import uk.ac.cam.cares.jps.sensor.source.worker.BufferFlushWorker;
import uk.ac.cam.cares.jps.sensor.source.worker.SensorUploadWorker;
import uk.ac.cam.cares.jps.sensor.source.worker.UnsentDataUploadWorker;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * A foreground service that keeps sensor recording running even when the app is terminated by user or the system.
 */
@AndroidEntryPoint
public class SensorService extends Service {

    private static final String UNIQUE_BUFFER_FLUSH_WORK = "pending_buffer_flush";
    private static final String UNIQUE_SENSOR_UPLOAD_WORK = "pending_sensor_upload";
    private static final String UNIQUE_UNSENT_UPLOAD_WORK = "pending_unsent_data_upload";

    @Inject
    SensorNetworkSource sensorNetworkSource;
    @Inject
    SensorHandlerManager sensorHandlerManager;
    @Inject
    SensorLocalSource sensorLocalSource;
    @Inject
    NetworkChangeReceiver networkChangeReceiver;
    @Inject
    SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository;

    private final int FOREGROUND_ID = 100;
    private final String CHANNEL_ID = "Sensors";
    private final int SENSOR_FRAGMENT_REQUEST_CODE = 100;
    private final Logger LOGGER = Logger.getLogger(SensorService.class);
    private SensorActivityClassifier activityClassifier;
    Timer bufferWorkerTimer = new Timer();
    Timer uploadWorkerTimer = new Timer();
    private Map<String, Integer> sensorSettingsMap;

    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private volatile String currentDeviceId;
    private volatile String currentSessionId;
    private volatile String currentSelectedSensorsJson;
    private volatile boolean isDestroyed = false;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorSettingsMap = loadSensorSettingsConfig(this);
    }

    private Map<String, Integer> loadSensorSettingsConfig(Context context) {
        Map<String, Integer> settings = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("sensor_settings_config.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);
            JSONObject configJson = new JSONObject(json);
            settings.put("activity_request_code", configJson.optInt("activity_recognition_request_code", 0));
            settings.put("activity_recognition_update_interval", configJson.optInt("activity_recognition_update_interval"));
            settings.put("buffer_delay", configJson.optInt("buffer_delay"));
            settings.put("upload_delay", configJson.optInt("upload_delay"));

        } catch (Exception e) {
            e.printStackTrace();
        }
        return settings;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null || intent.getExtras() == null) {
            LOGGER.warn("Self stop because deviceId unknown");
            stopSelf();
            return START_STICKY;
        }

        List<SensorType> selectedSensors = intent.getParcelableArrayListExtra("selectedSensors");
        if (selectedSensors == null || !startForegroundService(selectedSensors)) {
            stopSelf();
            return START_STICKY;
        }

        if (selectedSensors.isEmpty()) {
            LOGGER.warn("No sensors selected or sensor list is empty.");
            stopSelf();
            return START_STICKY;
        }

        // Start sensor handlers cleanly (clears any stale buffer from a stopped session)
        sensorHandlerManager.startSelectedSensors(selectedSensors);

        // Resolve session/task ID first so ActivityClassifier, BufferFlushWorker, and uploads
        // all stamp rows with this exact recording's sessionId.
        currentDeviceId = intent.getExtras().getString("deviceId");
        JSONArray jsonArray = new JSONArray();
        for (SensorType sensor : selectedSensors) {
            jsonArray.put(sensor.name());
        }
        currentSelectedSensorsJson = jsonArray.toString();

        sensorCollectionStateManagerRepository.getTaskId(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String id) {
                if (isDestroyed) {
                    return;
                }
                sensorNetworkSource.resetMessageId();

                String taskId;
                if (id == null || id.isEmpty()) {
                    taskId = UUID.randomUUID().toString();
                    sensorCollectionStateManagerRepository.setTaskId(taskId);
                    LOGGER.info("Started new recording with Task ID: " + taskId);
                } else {
                    taskId = id;
                    LOGGER.info("Resuming recording with existing Task ID: " + id);
                }
                currentSessionId = taskId;

                networkChangeReceiver.setTaskId(taskId);
                IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
                registerReceiver(networkChangeReceiver, filter);

                if (selectedSensors.contains(SensorType.ACTIVITY)) {
                    if (!checkActivityRecognitionPermission()) {
                        LOGGER.warn("Activity Recognition permission not granted: step detector unavailable, "
                                + "activity is classified from accelerometer and speed only.");
                    }
                    LocationHandler locationHandler = null;
                    if (selectedSensors.contains(SensorType.LOCATION)
                            && sensorHandlerManager.getSensorHandler(SensorType.LOCATION) instanceof LocationHandler) {
                        locationHandler = (LocationHandler) sensorHandlerManager.getSensorHandler(SensorType.LOCATION);
                    }
                    activityClassifier = new SensorActivityClassifier(
                            SensorService.this,
                            (SensorManager) getSystemService(SENSOR_SERVICE),
                            sensorLocalSource,
                            locationHandler,
                            taskId);
                    activityClassifier.start();
                }

                scheduleBufferFlushTask(taskId);
                scheduleUploadDataTask(taskId);
            }

            @Override
            public void onFailure(Throwable error) {
                LOGGER.warn("Not able to retrieve task id.");
                stopSelf();
            }
        });

        return START_STICKY;
    }

    private boolean startForegroundService(List<SensorType> selectedSensors) {
        boolean isLocationSensorToggled = selectedSensors.contains(SensorType.LOCATION);
        int type = 0;
        if (isLocationSensorToggled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LOGGER.info("location toggled true" + type);
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
        }

        Notification notification = getNotification();
        if (isLocationSensorToggled && type != 0) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                ServiceCompat.startForeground(this, FOREGROUND_ID, notification, type);
            } else {
                LOGGER.warn("Location permission not granted. Unable to start service with location type.");
                return false;
            }
        } else {
            ServiceCompat.startForeground(this, FOREGROUND_ID, notification, type);
        }

        return true;
    }

    private void scheduleBufferFlushTask(String sessionId) {
        long BUFFER_DELAY = sensorSettingsMap.get("buffer_delay");
        Data flushData = new Data.Builder()
                .putString("sessionId", sessionId)
                .build();
        bufferWorkerTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                OneTimeWorkRequest flushWork = new OneTimeWorkRequest.Builder(BufferFlushWorker.class)
                        .addTag("bufferFlushWork")
                        .setInputData(flushData)
                        .build();
                WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                        UNIQUE_BUFFER_FLUSH_WORK,
                        ExistingWorkPolicy.KEEP,
                        flushWork
                );
            }
        }, BUFFER_DELAY, BUFFER_DELAY);
    }

    private void scheduleUploadDataTask(String taskId) {
        Data uploadData = buildSensorUploadData();
        Constraints networkConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        long upload_delay = sensorSettingsMap.get("upload_delay");
        uploadWorkerTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(SensorUploadWorker.class)
                        .addTag("dataUploadWork")
                        .setConstraints(networkConstraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .setInputData(uploadData)
                        .build();
                WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                        UNIQUE_SENSOR_UPLOAD_WORK,
                        ExistingWorkPolicy.KEEP,
                        work
                );
            }
        }, upload_delay, upload_delay);
    }

    private Data buildSensorUploadData() {
        Data.Builder builder = new Data.Builder();
        if (currentDeviceId != null) {
            builder.putString("deviceId", currentDeviceId);
        }
        if (currentSessionId != null) {
            builder.putString("sessionId", currentSessionId);
        }
        if (currentSelectedSensorsJson != null) {
            builder.putString("selectedSensors", currentSelectedSensorsJson);
        }
        return builder.build();
    }

    private void enqueuePendingUploads(Context appContext, Data uploadData, String sessionId) {
        Constraints networkConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest finalSensorUpload = new OneTimeWorkRequest.Builder(SensorUploadWorker.class)
                .addTag("dataUploadWork")
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(uploadData)
                .build();

        Data unsentInput = new Data.Builder()
                .putString("taskId", sessionId)
                .build();
        OneTimeWorkRequest finalUnsentUpload = new OneTimeWorkRequest.Builder(UnsentDataUploadWorker.class)
                .addTag("unsentDataUploadWorker")
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(unsentInput)
                .build();

        WorkManager workManager = WorkManager.getInstance(appContext);
        workManager.enqueueUniqueWork(UNIQUE_SENSOR_UPLOAD_WORK, ExistingWorkPolicy.REPLACE, finalSensorUpload);
        workManager.enqueueUniqueWork(UNIQUE_UNSENT_UPLOAD_WORK, ExistingWorkPolicy.REPLACE, finalUnsentUpload);
    }

    @NonNull
    private Notification getNotification() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "SensorDataCollectionChannel", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("SensorDataCollection channel for foreground service notification");

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        Uri sensorFragmentLink = Uri.parse(getString(uk.ac.cam.cares.jps.utils.R.string.sensor_fragment_link));
        Intent sensorFragmentIntent = new Intent(Intent.ACTION_VIEW, sensorFragmentLink);
        sensorFragmentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sensorFragmentIntent.addCategory("uk.ac.cam.cares.jps.app");
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(),
                SENSOR_FRAGMENT_REQUEST_CODE,
                sensorFragmentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setContentText(getString(R.string.sensors_are_running_for_data_collection))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSmallIcon(uk.ac.cam.cares.jps.ui.base.R.drawable.twa_notification_icon)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private boolean checkActivityRecognitionPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            LOGGER.warn("Activity Recognition permission not granted.");
            return false;
        }
        return true;
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        sensorNetworkSource.resetMessageId();

        if (bufferWorkerTimer != null) {
            bufferWorkerTimer.cancel();
        }
        if (uploadWorkerTimer != null) {
            uploadWorkerTimer.cancel();
        }

        final Context appContext = getApplicationContext();
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_BUFFER_FLUSH_WORK);

        // Snapshot and clear in-memory sensor samples BEFORE stopping sensors so nothing leaks into a new session
        final Map<String, JSONArray> remainingData =
                sensorHandlerManager != null ? sensorHandlerManager.collectSensorData() : null;
        final Data finalUploadData = buildSensorUploadData();
        final String finalSessionId = currentSessionId;

        flushExecutor.execute(() -> {
            try {
                if (remainingData != null && !remainingData.isEmpty() && sensorLocalSource.isDatabaseInitialized()) {
                    sensorLocalSource.writeToDatabase(remainingData, finalSessionId);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to flush remaining buffered sensor data on service stop", e);
            } finally {
                enqueuePendingUploads(appContext, finalUploadData, finalSessionId);
            }
        });
        flushExecutor.shutdown();

        if (networkChangeReceiver != null) {
            try {
                LOGGER.info("unregister network change receiver");
                unregisterReceiver(networkChangeReceiver);
            } catch (Exception e) {
                LOGGER.warn("network change receiver is deregister already");
            }
        }

        if (activityClassifier != null) {
            activityClassifier.stop();
            activityClassifier = null;
        }

        LOGGER.info("Stopping sensor service");
        try {
            if (sensorHandlerManager != null) {
                sensorHandlerManager.stopSensors();
                LOGGER.info("Sensors have been stopped.");
            }

            sensorCollectionStateManagerRepository.getTaskId(new RepositoryCallback<>() {
                @Override
                public void onSuccess(String taskId) {
                    LOGGER.info("Stopping sensor service with Task ID: " + taskId);
                }

                @Override
                public void onFailure(Throwable error) {
                }
            });

            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            sensorCollectionStateManagerRepository.clearManager();

            LOGGER.info("Sensor service is stopped. Sensors stop recording.");
        } catch (NullPointerException exception) {
            LOGGER.warn("Foreground service has already stopped.");
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}