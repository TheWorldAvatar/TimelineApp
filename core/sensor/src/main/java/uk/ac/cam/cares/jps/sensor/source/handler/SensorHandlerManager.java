package uk.ac.cam.cares.jps.sensor.source.handler;

import static android.content.Context.SENSOR_SERVICE;

import android.content.Context;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

public class SensorHandlerManager {

    private final Logger LOGGER = Logger.getLogger(SensorHandlerManager.class);
    private final SensorHandler[] sensorHandlers;
    private final Map<SensorType, SensorHandler> sensorHandlersMap;
    private final Map<SensorType, Integer> samplingRatesMap;
    private final Object sessionLock = new Object();
    private String activeSessionId = null;

    @Inject
    public SensorHandlerManager(Context applicationContext) {
        android.hardware.SensorManager sensorManager = (android.hardware.SensorManager) applicationContext.getSystemService(SENSOR_SERVICE);
        samplingRatesMap = loadSamplingRatesConfig(applicationContext);
        sensorHandlersMap = new HashMap<>();

        sensorHandlersMap.put(SensorType.ACCELEROMETER, new AccelerometerHandler(sensorManager));
        sensorHandlersMap.put(SensorType.GYROSCOPE, new GyroscopeHandler(sensorManager));
        sensorHandlersMap.put(SensorType.MAGNETOMETER, new MagnetometerHandler(sensorManager));
        sensorHandlersMap.put(SensorType.LIGHT, new LightSensorHandler(sensorManager));
        sensorHandlersMap.put(SensorType.HUMIDITY, new RelativeHumiditySensorHandler(sensorManager));
        sensorHandlersMap.put(SensorType.PRESSURE, new PressureSensorHandler(sensorManager));
        sensorHandlersMap.put(SensorType.GRAVITY, new GravitySensorHandler(sensorManager));
        sensorHandlersMap.put(SensorType.LOCATION, new LocationHandler(applicationContext));
        sensorHandlersMap.put(SensorType.SOUND, new SoundLevelHandler(applicationContext, sensorManager));

        sensorHandlers = sensorHandlersMap.values().toArray(new SensorHandler[0]);
    }

    private Map<SensorType, Integer> loadSamplingRatesConfig(Context context) {
        Map<SensorType, Integer> rates = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("sensor_config.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);

            JSONObject config = new JSONObject(json).getJSONObject("samplingRates");
            rates.put(SensorType.ACCELEROMETER, parseSamplingRate(config.getString("accelerometer")));
            rates.put(SensorType.GYROSCOPE, parseSamplingRate(config.getString("gyroscope")));
            rates.put(SensorType.MAGNETOMETER, parseSamplingRate(config.getString("magnetometer")));
            rates.put(SensorType.LIGHT, parseSamplingRate(config.getString("light")));
            rates.put(SensorType.HUMIDITY, parseSamplingRate(config.getString("humidity")));
            rates.put(SensorType.PRESSURE, parseSamplingRate(config.getString("pressure")));
            rates.put(SensorType.GRAVITY, parseSamplingRate(config.getString("gravity")));
            rates.put(SensorType.LOCATION, parseSamplingRate(config.getString("location")));
            rates.put(SensorType.SOUND, parseSamplingRate(config.getString("sound")));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rates;
    }

    private int parseSamplingRate(String rate) {
        switch (rate) {
            case "SENSOR_DELAY_UI":
                return android.hardware.SensorManager.SENSOR_DELAY_UI;
            case "SENSOR_DELAY_GAME":
                return android.hardware.SensorManager.SENSOR_DELAY_GAME;
            case "SENSOR_DELAY_FASTEST":
                return android.hardware.SensorManager.SENSOR_DELAY_FASTEST;
            case "SENSOR_DELAY_NORMAL":
            default:
                return android.hardware.SensorManager.SENSOR_DELAY_NORMAL;
        }
    }

    public void startSelectedSensors(List<SensorType> selectedSensorTypes) {
        startSelectedSensors(selectedSensorTypes, null);
    }

    public void startSelectedSensors(List<SensorType> selectedSensorTypes, String sessionId) {
        synchronized (sessionLock) {
            // Stop any lingering handlers and discard any uncollected buffers before switching session
            stopSensorsLocked();
            clearAllSensorDataLocked();
            activeSessionId = sessionId;

            for (SensorType type : selectedSensorTypes) {
                SensorHandler handler = sensorHandlersMap.get(type);
                if (handler != null) {
                    handler.clearSensorData();
                    handler.start(samplingRatesMap.get(type));
                }
            }
            LOGGER.info("Selected sensors started for session " + sessionId);
        }
    }

    public void stopSensors() {
        synchronized (sessionLock) {
            stopSensorsLocked();
            clearAllSensorDataLocked();
            activeSessionId = null;
        }
    }

    public Map<String, JSONArray> stopSessionAndCollectRemaining(String sessionId) {
        synchronized (sessionLock) {
            if (sessionId == null || !sessionId.equals(activeSessionId)) {
                LOGGER.info("Skipping stop/drain for session " + sessionId + "; active session is " + activeSessionId);
                return new HashMap<>();
            }
            Map<String, JSONArray> remaining = collectSensorDataLocked();
            stopSensorsLocked();
            clearAllSensorDataLocked();
            activeSessionId = null;
            return remaining;
        }
    }

    public Map<String, JSONArray> collectSensorDataForSession(String expectedSessionId) {
        synchronized (sessionLock) {
            if (expectedSessionId == null || expectedSessionId.isEmpty() || !expectedSessionId.equals(activeSessionId)) {
                LOGGER.info("Ignoring stale buffer flush for session " + expectedSessionId
                        + " (activeSessionId=" + activeSessionId + ")");
                return new HashMap<>();
            }
            return collectSensorDataLocked();
        }
    }

    public Map<String, JSONArray> collectSensorData() {
        synchronized (sessionLock) {
            return collectSensorDataLocked();
        }
    }

    public void clearAllSensorData() {
        synchronized (sessionLock) {
            clearAllSensorDataLocked();
        }
    }

    private void stopSensorsLocked() {
        for (SensorHandler handler : sensorHandlers) {
            if (handler.isRunning()) {
                handler.stop();
            }
        }
        LOGGER.info("sensors stopped");
    }

    private void clearAllSensorDataLocked() {
        for (SensorHandler handler : sensorHandlersMap.values()) {
            handler.clearSensorData();
        }
    }

    private Map<String, JSONArray> collectSensorDataLocked() {
        Map<String, JSONArray> localStorageData = new HashMap<>();
        for (SensorHandler handler : sensorHandlersMap.values()) {
            if (handler.isRunning()) {
                JSONArray sensorData;
                synchronized (handler.getSensorDataLock()) {
                    sensorData = handler.getSensorData();
                    handler.clearSensorData();
                }
                if (sensorData.length() > 0) {
                    localStorageData.put(handler.getSensorName(), sensorData);
                }
            }
        }
        return localStorageData;
    }

    public SensorHandler getSensorHandler(SensorType type) {
        return sensorHandlersMap.get(type);
    }
}