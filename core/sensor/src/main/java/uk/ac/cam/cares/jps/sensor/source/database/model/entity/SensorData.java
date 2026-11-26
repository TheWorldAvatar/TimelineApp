package uk.ac.cam.cares.jps.sensor.source.database.model.entity;

import androidx.room.ColumnInfo;
import androidx.room.PrimaryKey;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * An abstract base class representing generic sensor data.
 * This class is extended by specific sensor data entities.
 * It provides common properties and methods for handling sensor data.
 */
public abstract class SensorData {

    @PrimaryKey
    public long time;
    public int uploaded;
    @ColumnInfo(name = "session_id")
    public String sessionId;

    /**
     * Default constructor
     */
    public SensorData() {

    }

    /**
     * Constructs a sensor data object using a JSONObject.
     * @param jo JSONObject which contains time data.
     */
    public SensorData(JSONObject jo) {
        this.uploaded = 0;
        try {
            this.time = jo.getLong("time") * 1000000;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the timestamp for upload: the stored time rounded to the nearest millisecond,
     * still expressed in nanoseconds (i.e. always a multiple of 1,000,000).
     * Use this instead of {@link #time} when building the payload in {@link #toJson()}.
     */
    public long timeNanosRoundedToMillis() {
        return Math.round(time / 1_000_000.0) * 1_000_000L;
    }

    public abstract String toJSONString();

    /**
     * Converts the current sensor data object into a {@link JSONObject} representation.
     * The resulting JSON object contains the sensor's name, its values, and the timestamp.
     * @return A {@link JSONObject} representation of the sensor data, with sensor name, values, and time.
     */
    public abstract JSONObject toJson();
}