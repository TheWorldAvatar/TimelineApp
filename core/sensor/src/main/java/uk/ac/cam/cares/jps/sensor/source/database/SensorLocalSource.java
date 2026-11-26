package uk.ac.cam.cares.jps.sensor.source.database;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.inject.Inject;

import uk.ac.cam.cares.jps.sensor.source.database.model.AppDatabase;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.AccelerationDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.ActivityDataDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.GravityDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.GyroDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.LightDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.LocationDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.MagnetFieldStrengthDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.PressureDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.RelativeHumidityDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.SensorDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.SoundLevelDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.dao.UnsentDataDao;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.Acceleration;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.ActivityData;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.Gravity;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.GyroData;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.LightData;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.LocationData;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.MagnetFieldStrength;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.Pressure;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.RelativeHumidity;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.SensorData;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.SoundLevel;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.UnsentData;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;


/**
 * A class that commits data to the local database.
 */
public class SensorLocalSource {
    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            String[] tables = {
                    "location", "acceleration", "gravity", "gyro", "light",
                    "magnet", "pressure", "humidity", "sound", "activity_data", "unsent_data"
            };
            for (String table : tables) {
                database.execSQL("ALTER TABLE " + table + " ADD COLUMN session_id TEXT");
            }
        }
    };

    Context context;
    public LocationDao locationDao;
    public AccelerationDao accelerationDao;
    public GravityDao gravityDao;
    public GyroDao gyroDao;
    public LightDao lightDao;
    public MagnetFieldStrengthDao magnetFieldStrengthDao;
    public PressureDao pressureDao;
    public UnsentDataDao unsentDataDao;
    public RelativeHumidityDao relativeHumidityDao;
    public SoundLevelDao soundLevelDao;
    public ActivityDataDao activityDataDao;
    Logger LOGGER = Logger.getLogger(SensorLocalSource.class);
    Map<String, JSONArray> unsentData;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();


    @Inject
    public SensorLocalSource(Context context) {
        this.context = context;
    }

    public synchronized boolean isDatabaseInitialized() {
        return locationDao != null && unsentDataDao != null;
    }

    public synchronized void initAppDataBase(String dbName) {
        AppDatabase appDatabase = Room.databaseBuilder(context.getApplicationContext(),
                        AppDatabase.class, dbName)
                .addMigrations(MIGRATION_4_5)
                .build();

        locationDao = appDatabase.locationDao();
        accelerationDao = appDatabase.accelerationDao();
        gravityDao = appDatabase.gravityDao();
        gyroDao = appDatabase.gyroDao();
        lightDao = appDatabase.lightDao();
        magnetFieldStrengthDao = appDatabase.magnetFieldStrengthDao();
        pressureDao = appDatabase.pressureDao();
        soundLevelDao = appDatabase.soundLevelDao();
        relativeHumidityDao = appDatabase.relativeHumidityDao();
        this.unsentDataDao = appDatabase.unsentDataDao();
        activityDataDao = appDatabase.activityDataDao();
        this.unsentData = new HashMap<>();
    }

    public void saveActivityData(String activityType, int confidence, long timestamp) {
        saveActivityData(activityType, confidence, timestamp, null);
    }

    /**
     * Commits activity data to the local database tagged with its recording sessionId.
     */
    public void saveActivityData(String activityType, int confidence, long timestamp, String sessionId) {
        ActivityData activityData = new ActivityData(activityType, confidence, timestamp);
        activityData.sessionId = sessionId;
        activityDataDao.insert(activityData);
    }

    public void writeToDatabase(Map<String, JSONArray> allSensorData) {
        writeToDatabase(allSensorData, null);
    }

    /**
     * Commits sensor data to the local database tagged with its recording sessionId.
     */
    public void writeToDatabase(Map<String, JSONArray> allSensorData, String sessionId) {
        LOGGER.info("Start to write to database for session: " + sessionId);

        writeToDatabaseHelper(allSensorData, SensorType.LOCATION.getSensorName(), locationDao, LocationData.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.ACCELEROMETER.getSensorName(), accelerationDao, Acceleration.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.GRAVITY.getSensorName(), gravityDao, Gravity.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.GYROSCOPE.getSensorName(), gyroDao, GyroData.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.LIGHT.getSensorName(), lightDao, LightData.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.MAGNETOMETER.getSensorName(), magnetFieldStrengthDao, MagnetFieldStrength.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.PRESSURE.getSensorName(), pressureDao, Pressure.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.SOUND.getSensorName(), soundLevelDao, SoundLevel.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.HUMIDITY.getSensorName(), relativeHumidityDao, RelativeHumidity.class, sessionId);
        writeToDatabaseHelper(allSensorData, SensorType.ACTIVITY.getSensorName(), activityDataDao, ActivityData.class, sessionId);
    }

    private <T extends SensorData> void writeToDatabaseHelper(Map<String, JSONArray> allSensorData, String sensorType,
                                                              SensorDao<T> sensorDao, Class<T> sensorClass, String sessionId) {
        JSONArray sensorArray = allSensorData.get(sensorType);

        if (sensorArray != null) {
            try {
                Map<Long, T> sensorDataList = new HashMap<>();
                for (int i = 0; i < sensorArray.length(); i++) {
                    JSONObject jsonObject = sensorArray.getJSONObject(i);
                    T sensorData = sensorClass.getConstructor(JSONObject.class).newInstance(jsonObject);
                    sensorData.sessionId = sessionId;
                    sensorDataList.put(sensorData.time, sensorData);
                }

                T[] sensorDataArray = sensorDataList.values().toArray((T[]) java.lang.reflect.Array.newInstance(sensorClass, sensorDataList.size()));
                sensorDao.insertAll(sensorDataArray);
            } catch (JSONException | NoSuchMethodException e) {
                LOGGER.error("Error processing sensor data for type " + sensorType + ": " + e.getMessage());
                throw new RuntimeException("Could not write data to database", e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Inserts an unsent data record into the local database.
     */
    public void insertUnsentData(UnsentData unsentData) {
        try {
            executor.execute(() -> this.unsentDataDao.insert(unsentData));
            Log.e("local source", "unsent data inserted");
        } catch (Exception e) {
            Log.e("local source", "error inserting unsent data", e);
        }
    }

    /**
     * Retrieves unsent data from the local database.
     */
    public List<UnsentData> retrieveUnsentData(int limit, int offset) {
        Cursor cursor = unsentDataDao.getAllUnsentData(limit, offset);
        List<UnsentData> unsentDataList = new ArrayList<>();

        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (cursor.getPosition() < cursor.getCount()) {
                        UnsentData data = mapCursorToUnsentData(cursor);
                        unsentDataList.add(data);
                    }
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return unsentDataList;
    }

    private UnsentData mapCursorToUnsentData(Cursor cursor) {
        UnsentData unsentData = new UnsentData();

        unsentData.id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
        unsentData.data = cursor.getString(cursor.getColumnIndexOrThrow("data"));
        unsentData.deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id"));
        int sessionIdx = cursor.getColumnIndex("session_id");
        unsentData.sessionId = sessionIdx >= 0 ? cursor.getString(sessionIdx) : null;
        unsentData.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
        unsentData.dataHash = cursor.getString(cursor.getColumnIndexOrThrow("dataHash"));

        return unsentData;
    }

    public void deleteUnsentData(UnsentData unsentData) {
        try {
            executor.execute(() -> unsentDataDao.deleteById(unsentData.id));
            Log.i("local source", "unsent data deleted");
        } catch (Exception e) {
            Log.e("local source", "error deleting unsent data", e);
        }
    }

    public void deleteUnsentData(List<UnsentData> unsentDataList) {
        try {
            Future<?> future = executor.submit(() -> unsentDataDao.deleteByIds(unsentDataList.stream().map(d -> d.id)
                    .collect(Collectors.toList())));
            future.get();
            Log.i("local source", "unsent data deleted");
        } catch (Exception e) {
            Log.e("local source", "error deleting unsent data", e);
        }
    }

    /**
     * Deletes data stored longer than a determined cutoff time.
     */
    public void deleteHistoricalData(long cutoffTime) {
        locationDao.delete(cutoffTime);
        accelerationDao.delete(cutoffTime);
        gravityDao.delete(cutoffTime);
        gyroDao.delete(cutoffTime);
        lightDao.delete(cutoffTime);
        magnetFieldStrengthDao.delete(cutoffTime);
        pressureDao.delete(cutoffTime);
        soundLevelDao.delete(cutoffTime);
        relativeHumidityDao.delete(cutoffTime);
        activityDataDao.delete(cutoffTime);
    }

    /**
     * Retrieves un-uploaded sensor data grouped strictly by session_id so points from one
     * recording session are never mixed into another session's upload payload.
     */
    public Map<String, JSONArray> retrieveUnUploadedSensorDataBySession(
            List<SensorType> selectedSensors,
            int limit,
            int offset,
            Map<String, Map<SensorType, List<Long>>> timesBySessionAndSensorOut,
            boolean[] anySensorHitLimitOut) {

        Map<String, List<SensorData>> rowsBySession = new LinkedHashMap<>();
        boolean hitLimit = false;

        for (SensorType sensor : selectedSensors) {
            List<? extends SensorData> list = fetchUnUploadedForSensor(sensor, limit, offset);
            if (list.size() >= limit) {
                hitLimit = true;
            }
            for (SensorData data : list) {
                String sessionKey = data.sessionId != null ? data.sessionId : "";
                rowsBySession.computeIfAbsent(sessionKey, k -> new ArrayList<>()).add(data);
                timesBySessionAndSensorOut
                        .computeIfAbsent(sessionKey, k -> new HashMap<>())
                        .computeIfAbsent(sensor, k -> new ArrayList<>())
                        .add(data.time);
            }
        }

        if (anySensorHitLimitOut != null && anySensorHitLimitOut.length > 0) {
            anySensorHitLimitOut[0] = hitLimit;
        }

        Map<String, JSONArray> arraysBySession = new LinkedHashMap<>();
        int duplicates = 0;

        for (Map.Entry<String, List<SensorData>> entry : rowsBySession.entrySet()) {
            JSONArray sessionArray = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (SensorData sensorData : entry.getValue()) {
                JSONObject json = sensorData.toJson();
                String key = json.optString("name") + "@" + json.optLong("time");
                if (!seen.add(key)) {
                    duplicates++;
                    continue;
                }
                sessionArray.put(json);
            }
            if (sessionArray.length() > 0) {
                arraysBySession.put(entry.getKey(), sessionArray);
            }
        }

        if (duplicates > 0) {
            LOGGER.info("Removed " + duplicates + " duplicate record(s) from upload batches");
        }
        return arraysBySession;
    }

    private List<? extends SensorData> fetchUnUploadedForSensor(SensorType sensor, int limit, int offset) {
        switch (sensor) {
            case LOCATION: return Arrays.asList(locationDao.getAllUnUploadedData(limit, offset));
            case ACCELEROMETER: return Arrays.asList(accelerationDao.getAllUnUploadedData(limit, offset));
            case GRAVITY: return Arrays.asList(gravityDao.getAllUnUploadedData(limit, offset));
            case GYROSCOPE: return Arrays.asList(gyroDao.getAllUnUploadedData(limit, offset));
            case LIGHT: return Arrays.asList(lightDao.getAllUnUploadedData(limit, offset));
            case MAGNETOMETER: return Arrays.asList(magnetFieldStrengthDao.getAllUnUploadedData(limit, offset));
            case PRESSURE: return Arrays.asList(pressureDao.getAllUnUploadedData(limit, offset));
            case SOUND: return Arrays.asList(soundLevelDao.getAllUnUploadedData(limit, offset));
            case HUMIDITY: return Arrays.asList(relativeHumidityDao.getAllUnUploadedData(limit, offset));
            case ACTIVITY: return Arrays.asList(activityDataDao.getAllUnUploadedData(limit, offset));
            default: return new ArrayList<>();
        }
    }

    public JSONArray retrieveUnUploadedSensorData(List<SensorType> selectedSensors, int limit, int offset,
                                                  Map<SensorType, List<Long>> timesBySensorOut) {
        List<SensorData> allSensorData = new ArrayList<>();

        for (SensorType sensor : selectedSensors) {
            List<? extends SensorData> list = fetchUnUploadedForSensor(sensor, limit, offset);
            allSensorData.addAll(list);
            timesBySensorOut.put(sensor, extractTimes(list));
        }

        JSONArray allSensorDataArray = new JSONArray();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (SensorData sensorData : allSensorData) {
            JSONObject json = sensorData.toJson();
            String key = json.optString("name") + "@" + json.optLong("time");
            if (!seen.add(key)) {
                duplicates++;
                continue;
            }
            allSensorDataArray.put(json);
        }
        if (duplicates > 0) {
            LOGGER.info("Removed " + duplicates + " duplicate record(s) from upload batch");
        }
        return allSensorDataArray;
    }

    /**
     * Call this ONLY after a synchronous send has confirmed the server received
     * the data. Never call it before sending, and never on a failed send.
     */
    public void markSensorDataAsUploaded(Map<SensorType, List<Long>> timesBySensor) {
        if (timesBySensor == null) {
            return;
        }
        for (Map.Entry<SensorType, List<Long>> entry : timesBySensor.entrySet()) {
            List<Long> times = entry.getValue();
            if (times.isEmpty()) {
                continue;
            }
            switch (entry.getKey()) {
                case LOCATION: locationDao.markAsUploaded(times); break;
                case ACCELEROMETER: accelerationDao.markAsUploaded(times); break;
                case GRAVITY: gravityDao.markAsUploaded(times); break;
                case GYROSCOPE: gyroDao.markAsUploaded(times); break;
                case LIGHT: lightDao.markAsUploaded(times); break;
                case MAGNETOMETER: magnetFieldStrengthDao.markAsUploaded(times); break;
                case PRESSURE: pressureDao.markAsUploaded(times); break;
                case SOUND: soundLevelDao.markAsUploaded(times); break;
                case HUMIDITY: relativeHumidityDao.markAsUploaded(times); break;
                case ACTIVITY: activityDataDao.markAsUploaded(times); break;
            }
        }
    }

    private List<Long> extractTimes(List<? extends SensorData> sensorDataList) {
        List<Long> times = new ArrayList<>();
        for (SensorData data : sensorDataList) {
            times.add(data.time);
        }
        return times;
    }

    /**
     * Checks if the given data already exists in the UnsentData table.
     */
    public boolean isDataInUnsentData(String dataHash) {

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<UnsentData> future = executor.submit(() -> unsentDataDao.getUnsentDataByHash(dataHash));

        try {
            UnsentData existingData = future.get();
            return existingData != null;
        } catch (InterruptedException | ExecutionException e) {
            Log.e("SensorLocalSource", "Error checking for unsent data", e);
            return false;
        } finally {
            executor.shutdown();
        }
    }
}