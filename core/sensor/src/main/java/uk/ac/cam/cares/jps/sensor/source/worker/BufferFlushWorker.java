package uk.ac.cam.cares.jps.sensor.source.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.apache.log4j.Logger;
import org.json.JSONArray;

import java.util.Map;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorHandlerManager;

@HiltWorker
public class BufferFlushWorker extends Worker {

    private final SensorHandlerManager sensorHandlerManager;
    private final SensorLocalSource sensorLocalSource;
    private final String sessionId;

    private final Logger LOGGER = Logger.getLogger(BufferFlushWorker.class);
    private static final long THIRTY_DAYS_IN_MILLIS = 30L * 24 * 60 * 60 * 1000;

    @AssistedInject
    public BufferFlushWorker(@Assisted @NonNull Context context, @Assisted @NonNull WorkerParameters workerParams,
                             SensorHandlerManager sensorHandlerManager,
                             SensorLocalSource sensorLocalSource) {
        super(context, workerParams);
        this.sensorHandlerManager = sensorHandlerManager;
        this.sensorLocalSource = sensorLocalSource;
        this.sessionId = workerParams.getInputData().getString("sessionId");
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (!sensorLocalSource.isDatabaseInitialized() || sessionId == null || sessionId.isEmpty()) {
                return Result.success();
            }

            Map<String, JSONArray> localData = sensorHandlerManager.collectSensorDataForSession(sessionId);
            if (!localData.isEmpty()) {
                sensorLocalSource.writeToDatabase(localData, sessionId);
                LOGGER.info("Buffer flushed to local storage for session " + sessionId);
            }

            long cutoffTime = System.currentTimeMillis() - THIRTY_DAYS_IN_MILLIS;
            sensorLocalSource.deleteHistoricalData(cutoffTime);

            return Result.success();
        } catch (Exception e) {
            LOGGER.error("Error flushing buffer", e);
            return Result.retry();
        }
    }
}