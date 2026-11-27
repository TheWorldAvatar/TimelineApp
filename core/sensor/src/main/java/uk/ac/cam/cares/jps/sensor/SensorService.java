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
import uk.ac.cam.cares.jps.sensor.source.worker.BufferFlushWorker;
import uk.ac.cam.cares.jps.sensor.source.worker.SensorUploadWorker;
import uk.ac.cam.cares.jps.sensor.source.worker.UnsentDataUploadWorker;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@AndroidEntryPoint
public class SensorService extends Service {

    private static final String UNIQUE_BUFFER_FLUSH_WORK = "pending_buffer_flush";
    private static final String UNIQUE_SENSOR_UPLOAD_WORK = "pending_sensor_upload";
    private static final String UNIQUE_UNSENT_UPLOAD_WORK = "pending_unsent_data_upload";

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
    private Timer bufferWorkerTimer;
    private Timer uploadWorkerTimer;
    private Map<String, Integer> sensorSettingsMap;

    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private volatile String currentDeviceId;
    private volatile String currentSessionId;
    private volatile String currentSelectedSensorsJson;
    private volatile boolean isDestroyed = false;
    private boolean isNetworkReceiverRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        isDestroyed = false;
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
            return START_NOT_STICKY;
        }

        List<SensorType> selectedSensors = intent.getParcelableArrayListExtra("selectedSensors");
        if (selectedSensors == null || !startForegroundService(selectedSensors)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (selectedSensors.isEmpty()) {
            LOGGER.warn("No sensors selected or sensor list is empty.");
            stopSelf();
            return START_NOT_STICKY;
        }

        currentDeviceId = intent.getExtras().getString("deviceId");
        JSONArray jsonArray = new JSONArray();
        for (SensorType sensor : selectedSensors) {
            jsonArray.put(sensor.name());
        }
        currentSelectedSensorsJson = jsonArray.toString();

        String intentSessionId = intent.getStringExtra("sessionId");
        if (intentSessionId != null && !intentSessionId.isEmpty()) {
            startSession(intentSessionId, selectedSensors);
        } else {
            sensorCollectionStateManagerRepository.getTaskId(new RepositoryCallback<>() {
                @Override
                public void onSuccess(String id) {
                    if (isDestroyed) {
                        return;
                    }
                    String taskId = (id != null && !id.isEmpty()) ? id : UUID.randomUUID().toString();
                    if (id == null || id.isEmpty()) {
                        sensorCollectionStateManagerRepository.setTaskId(taskId);
                    }
                    startSession(taskId, selectedSensors);
                }

                @Override
                public void onFailure(Throwable error) {
                    LOGGER.warn("Not able to retrieve task id.");
                    stopSelf();
                }
            });
        }

        return START_STICKY;
    }

    private void startSession(String sessionId, List<SensorType> selectedSensors) {
        if (isDestroyed) {
            return;
        }

        // Clean up any prior session timers/classifier if onStartCommand was called again
        cancelTimers();
        if (currentSessionId != null && !currentSessionId.equals(sessionId)) {
            WorkManager.getInstance(getApplicationContext())
                    .cancelUniqueWork(UNIQUE_BUFFER_FLUSH_WORK + "_" + currentSessionId);
        }
        if (activityClassifier != null) {
            activityClassifier.stop();
            activityClassifier = null;
        }

        currentSessionId = sessionId;
        LOGGER.info("Recording active with Session ID: " + sessionId);

        // Start sensors ONLY after currentSessionId and SensorHandlerManager's activeSessionId match
        sensorHandlerManager.startSelectedSensors(selectedSensors, sessionId);

        networkChangeReceiver.setTaskId(sessionId);
        if (!isNetworkReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(networkChangeReceiver, filter);
            isNetworkReceiverRegistered = true;
        }

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
                    sessionId);
            activityClassifier.start();
        }

        scheduleBufferFlushTask(sessionId);
        scheduleUploadDataTask(sessionId);
    }

    private void cancelTimers() {
        if (bufferWorkerTimer != null) {
            bufferWorkerTimer.cancel();
            bufferWorkerTimer = null;
        }
        if (uploadWorkerTimer != null) {
            uploadWorkerTimer.cancel();
            uploadWorkerTimer = null;
        }
    }

    private boolean startForegroundService(List<SensorType> selectedSensors) {
        boolean isLocationSensorToggled = selectedSensors.contains(SensorType.LOCATION);
        int type = 0;
        if (isLocationSensorToggled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        ServiceCompat.startForeground(this, FOREGROUND_ID, notification, type);
        return true;
    }

    private void scheduleBufferFlushTask(String sessionId) {
        Data flushData = new Data.Builder()
                .putString("sessionId", sessionId)
                .build();
        long BUFFER_DELAY = sensorSettingsMap.get("buffer_delay");
        final String uniqueWorkName = UNIQUE_BUFFER_FLUSH_WORK + "_" + sessionId;

        bufferWorkerTimer = new Timer();
        bufferWorkerTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (isDestroyed || !sessionId.equals(currentSessionId)) {
                    return;
                }
                OneTimeWorkRequest flushWork = new OneTimeWorkRequest.Builder(BufferFlushWorker.class)
                        .addTag("bufferFlushWork")
                        .setInputData(flushData)
                        .build();
                WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                        uniqueWorkName,
                        ExistingWorkPolicy.KEEP,
                        flushWork
                );
            }
        }, BUFFER_DELAY, BUFFER_DELAY);
    }

    private void scheduleUploadDataTask(String sessionId) {
        Data uploadData = buildSensorUploadData(sessionId);
        Constraints networkConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        long uploadDelay = sensorSettingsMap.get("upload_delay");

        uploadWorkerTimer = new Timer();
        uploadWorkerTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (isDestroyed || !sessionId.equals(currentSessionId)) {
                    return;
                }
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
        }, uploadDelay, uploadDelay);
    }

    private Data buildSensorUploadData(String sessionId) {
        Data.Builder builder = new Data.Builder();
        if (currentDeviceId != null) {
            builder.putString("deviceId", currentDeviceId);
        }
        if (sessionId != null) {
            builder.putString("sessionId", sessionId);
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
        // APPEND_OR_REPLACE ensures a running upload or new session isn't clobbered,
        // while guaranteeing a pass runs after remainingData is written to Room.
        workManager.enqueueUniqueWork(UNIQUE_SENSOR_UPLOAD_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, finalSensorUpload);
        workManager.enqueueUniqueWork(UNIQUE_UNSENT_UPLOAD_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, finalUnsentUpload);
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
        cancelTimers();

        final Context appContext = getApplicationContext();
        final String finalSessionId = currentSessionId;
        currentSessionId = null;

        if (finalSessionId != null && !finalSessionId.isEmpty()) {
            WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_BUFFER_FLUSH_WORK + "_" + finalSessionId);
        }

        if (isNetworkReceiverRegistered) {
            try {
                unregisterReceiver(networkChangeReceiver);
            } catch (Exception e) {
                LOGGER.warn("network change receiver already unregistered");
            }
            isNetworkReceiverRegistered = false;
        }

        if (activityClassifier != null) {
            activityClassifier.stop();
            activityClassifier = null;
        }

        // Atomically snapshot remaining samples & stop handlers ONLY if finalSessionId still owns SensorHandlerManager
        final Map<String, JSONArray> remainingData = sensorHandlerManager != null
                ? sensorHandlerManager.stopSessionAndCollectRemaining(finalSessionId)
                : null;
        final Data finalUploadData = buildSensorUploadData(finalSessionId);

        flushExecutor.execute(() -> {
            try {
                if (finalSessionId != null && !finalSessionId.isEmpty()
                        && remainingData != null && !remainingData.isEmpty()
                        && sensorLocalSource.isDatabaseInitialized()) {
                    sensorLocalSource.writeToDatabase(remainingData, finalSessionId);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to flush remaining buffered sensor data on service stop", e);
            } finally {
                enqueuePendingUploads(appContext, finalUploadData, finalSessionId);
            }
        });
        flushExecutor.shutdown();

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            LOGGER.info("Sensor service stopped cleanly for session: " + finalSessionId);
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