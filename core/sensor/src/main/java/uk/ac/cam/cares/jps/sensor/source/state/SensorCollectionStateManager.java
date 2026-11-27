package uk.ac.cam.cares.jps.sensor.source.state;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.utils.Utils;

public class SensorCollectionStateManager {
    private static final String DEVICE_ID_KEY = "device_id";
    private static final String USER_ID_KEY = "user_id";
    private static final String RECORDING_STATE_KEY = "recording_state";
    private static final String TASK_ID_KEY = "task_id";
    private static final String SELECTED_SENSORS_KEY = "selected_sensors";

    private final AtomicReference<SensorCollectionState> sensorCollectionState = new AtomicReference<>();
    private final Object stateLock = new Object();
    private final Context context;
    private final Logger LOGGER = Logger.getLogger(SensorCollectionStateManager.class);

    public SensorCollectionStateManager(Context context) {
        this.context = context;
    }

    public void initSensorCollectionState(String userId) {
        synchronized (stateLock) {
            String sharedPrefFileName = Utils.getHashedFileName(userId);
            File sharedPrefsFile = new File(context.getApplicationInfo().dataDir + "/shared_prefs/" + sharedPrefFileName + ".xml");

            SharedPreferences sharedPreferences = getSharedPreferences(sharedPrefFileName);
            String deviceId = "";
            boolean recordingState = false;
            String taskId = "";

            if (sharedPrefsFile.exists()) {
                deviceId = sharedPreferences.getString(DEVICE_ID_KEY, "");
                recordingState = sharedPreferences.getBoolean(RECORDING_STATE_KEY, false);
                taskId = sharedPreferences.getString(TASK_ID_KEY, "");
            }

            if (deviceId.isEmpty()) {
                deviceId = UUID.randomUUID().toString();
                recordingState = false;
                taskId = "";

                sharedPreferences.edit()
                        .putString(USER_ID_KEY, userId)
                        .putString(DEVICE_ID_KEY, deviceId)
                        .putBoolean(RECORDING_STATE_KEY, false)
                        .putString(TASK_ID_KEY, "")
                        .commit();
            }

            Set<String> selectedSensorNames;
            if (sharedPreferences.contains(SELECTED_SENSORS_KEY)) {
                selectedSensorNames = sharedPreferences.getStringSet(SELECTED_SENSORS_KEY, new HashSet<>());
            } else {
                selectedSensorNames = new HashSet<>();
                for (SensorType type : SensorType.values()) {
                    selectedSensorNames.add(type.name());
                }
                sharedPreferences.edit().putStringSet(SELECTED_SENSORS_KEY, selectedSensorNames).commit();
            }

            List<SensorType> loadedSelectedSensors = new ArrayList<>();
            for (String sensorName : selectedSensorNames) {
                try {
                    loadedSelectedSensors.add(SensorType.valueOf(sensorName));
                } catch (IllegalArgumentException ignored) {
                }
            }

            SensorCollectionState state = new SensorCollectionState(userId, deviceId, recordingState, taskId);
            state.setSelectedSensors(loadedSelectedSensors);
            sensorCollectionState.set(state);
        }
    }

    public void setTaskId(String taskId) {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                LOGGER.warn("setTaskId called while sensorCollectionState is null; ignoring value: " + taskId);
                return;
            }
            String safeTaskId = taskId != null ? taskId : "";
            state.setTaskId(safeTaskId);
            SharedPreferences sharedPreferences = getSharedPreferences(Utils.getHashedFileName(state.getUserId()));
            sharedPreferences.edit()
                    .putString(TASK_ID_KEY, safeTaskId)
                    .commit();
        }
    }

    public void clearTaskId(String userId) {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state != null) {
                state.setTaskId("");
                state.setRecordingState(false);
            }
            if (userId != null && !userId.isEmpty()) {
                SharedPreferences sharedPreferences = getSharedPreferences(Utils.getHashedFileName(userId));
                sharedPreferences.edit()
                        .putString(TASK_ID_KEY, "")
                        .putBoolean(RECORDING_STATE_KEY, false)
                        .commit();
            }
        }
    }

    public void beginNewSession(String sessionId, List<SensorType> selectedSensors) {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                return;
            }
            List<SensorType> sensorsCopy = selectedSensors != null ? new ArrayList<>(selectedSensors) : new ArrayList<>();
            state.setTaskId(sessionId);
            state.setRecordingState(true);
            state.setSelectedSensors(sensorsCopy);

            Set<String> sensorNames = new HashSet<>();
            for (SensorType sensor : sensorsCopy) {
                sensorNames.add(sensor.name());
            }

            SharedPreferences sharedPreferences = getSharedPreferences(Utils.getHashedFileName(state.getUserId()));
            sharedPreferences.edit()
                    .putString(TASK_ID_KEY, sessionId)
                    .putBoolean(RECORDING_STATE_KEY, true)
                    .putStringSet(SELECTED_SENSORS_KEY, sensorNames)
                    .commit();
        }
    }

    public String getTaskId() throws SensorCollectionStateException {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                throw new SensorCollectionStateException("SensorCollectionState is null. Need to reinitialize with userId.");
            }
            return state.getTaskId();
        }
    }

    public SensorCollectionState getSensorCollectionState() {
        return sensorCollectionState.get();
    }

    public void setRecordingState(Boolean isRecording) {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                return;
            }
            state.setRecordingState(isRecording);
            SharedPreferences sharedPreferences = getSharedPreferences(Utils.getHashedFileName(state.getUserId()));
            sharedPreferences.edit()
                    .putBoolean(RECORDING_STATE_KEY, isRecording)
                    .commit();
        }
    }

    public boolean getRecordingState() throws SensorCollectionStateException {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                throw new SensorCollectionStateException("SensorCollectionState is null. Need to reinitialize with userId.");
            }
            return state.getRecordingState();
        }
    }

    public void clearState(String userId) {
        synchronized (stateLock) {
            sensorCollectionState.set(null);
            LOGGER.info("sensor collection state is set to null");

            SharedPreferences sharedPreferences = getSharedPreferences(Utils.getHashedFileName(userId));
            sharedPreferences.edit()
                    .putBoolean(RECORDING_STATE_KEY, false)
                    .putString(TASK_ID_KEY, "")
                    .commit();
        }
    }

    public String getDeviceId() throws SensorCollectionStateException {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                throw new SensorCollectionStateException("SensorCollectionState is null. Need to reinitialize with userId.");
            }
            return state.getDeviceId();
        }
    }

    public String getUserId() throws SensorCollectionStateException {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                throw new SensorCollectionStateException("SensorCollectionState is null. Need to reinitialize with userId.");
            }
            return state.getUserId();
        }
    }

    private SharedPreferences getSharedPreferences(String sharedPrefFileName) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    sharedPrefFileName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            LOGGER.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public String getHashedFileName() throws SensorCollectionStateException {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                throw new SensorCollectionStateException("SensorCollectionState is null. Need to reinitialize with userId.");
            }
            return Utils.getHashedFileName(state.getUserId());
        }
    }

    public List<SensorType> getSelectedSensors() throws SensorCollectionStateException {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                throw new SensorCollectionStateException("SensorCollectionState is null. Need to reinitialize with userId.");
            }
            return state.getSelectedSensors();
        }
    }

    public void setSelectedSensors(List<SensorType> sensors) {
        synchronized (stateLock) {
            SensorCollectionState state = sensorCollectionState.get();
            if (state == null) {
                return;
            }
            state.setSelectedSensors(sensors);
            SharedPreferences sharedPreferences = getSharedPreferences(Utils.getHashedFileName(state.getUserId()));
            Set<String> sensorNames = new HashSet<>();
            for (SensorType sensor : sensors) {
                sensorNames.add(sensor.name());
            }
            sharedPreferences.edit()
                    .putStringSet(SELECTED_SENSORS_KEY, sensorNames)
                    .commit();
        }
    }
}