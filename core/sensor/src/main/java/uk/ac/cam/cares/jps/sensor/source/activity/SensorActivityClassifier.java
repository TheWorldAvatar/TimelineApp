package uk.ac.cam.cares.jps.sensor.source.activity;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.core.content.ContextCompat;

import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.handler.LocationHandler;

/**
 * Classifies the user's activity from the phone's own sensors, with NO Google Play services.
 *
 * Labels are the same ones the rest of the app already uses: "still", "walking", "bike",
 * "vehicle" and "others". Every result is stored with a rough confidence (0-100).
 *
 * Every WINDOW_MS the classifier looks at three features:
 *   - motion intensity: standard deviation of the accelerometer magnitude (gravity cancels out)
 *   - step cadence: steps per second, from TYPE_STEP_DETECTOR when available and permitted,
 *     otherwise from counting peaks in the accelerometer signal
 *   - speed: from the location handler's latest fix (only when it is recent)
 *
 * Rules turn those into a label and a confidence. Agreement between independent signals
 * (e.g. steps AND walking speed) gives high confidence; a guess from one signal gives low.
 * The last SMOOTHING_WINDOWS results are then combined, so a single odd window can neither
 * flip the label nor keep full confidence.
 *
 * NOTE: the thresholds are starting values, not tuned on real data. They are all constants at
 * the top of this class; adjust them using the logged features ("ACTIVITY window: ...").
 */
public class SensorActivityClassifier implements SensorEventListener {

    // ---- timing -----------------------------------------------------------------------------
    private static final long WINDOW_MS = 5_000;
    private static final int SMOOTHING_WINDOWS = 3;
    private static final long MAX_SPEED_AGE_MS = 10_000;
    private static final int MIN_SAMPLES_PER_WINDOW = 50;   // ~1/5 of the expected 50 Hz * 5 s
    private static final int EXPECTED_SAMPLES_PER_WINDOW = 250;

    // ---- thresholds -------------------------------------------------------------------------
    private static final double STILL_SIGMA = 0.12;          // m/s^2, below = phone not moving
    private static final double BUMPY_SIGMA = 0.60;          // m/s^2, pedalling / rough surface
    private static final double STEP_CADENCE_MIN = 0.8;      // steps/s, below = no stepping
    private static final double RUNNING_CADENCE_MIN = 2.2;   // steps/s
    private static final double SPEED_STILL = 0.5;           // m/s
    private static final double SPEED_WALK_MAX = 2.8;        // m/s
    private static final double SPEED_RUN_MAX = 5.5;         // m/s
    private static final double SPEED_BIKE_MAX = 7.0;        // m/s
    private static final double SPEED_VEHICLE = 8.0;         // m/s

    // ---- peak counting fallback (when no step detector) --------------------------------------
    private static final double PEAK_THRESHOLD = 0.8;        // m/s^2 above the running mean
    private static final long MIN_PEAK_GAP_MS = 300;

    /** Results below this confidence are not stored. */
    public static final int MIN_CONFIDENCE_TO_RECORD = 50;

    private final Logger LOGGER = Logger.getLogger(SensorActivityClassifier.class);
    private final Context context;
    private final SensorManager sensorManager;
    private final SensorLocalSource sensorLocalSource;
    private final LocationHandler locationHandler; // may be null when location is not recorded
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private HandlerThread handlerThread;
    private boolean running = false;
    private boolean useStepDetector = false;

    // window accumulators (only touched on the handler thread)
    private long windowStartMs;
    private int sampleCount;
    private double mean;
    private double m2;
    private int detectorSteps;
    private int peakSteps;
    private double runningMean = 9.81;
    private boolean aboveThreshold = false;
    private long lastPeakMs = 0;

    private final Deque<Result> recent = new ArrayDeque<>();

    private static class Result {
        final String label;
        final int confidence;

        Result(String label, int confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }

    public SensorActivityClassifier(Context context,
                                    SensorManager sensorManager,
                                    SensorLocalSource sensorLocalSource,
                                    LocationHandler locationHandler) {
        this.context = context;
        this.sensorManager = sensorManager;
        this.sensorLocalSource = sensorLocalSource;
        this.locationHandler = locationHandler;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            LOGGER.error("ACTIVITY: no accelerometer on this device, cannot classify activity");
            return;
        }
        handlerThread = new HandlerThread("ActivityClassifier");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        resetWindow(System.currentTimeMillis());
        recent.clear();

        // ~50 Hz is needed to see walking cadence (SENSOR_DELAY_NORMAL is ~5 Hz)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler);

        Sensor stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        boolean permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        useStepDetector = stepDetector != null && permitted
                && sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL, handler);
        LOGGER.info("ACTIVITY classifier started. step detector: " + useStepDetector
                + (useStepDetector ? "" : " (using accelerometer peaks instead)")
                + ", speed source: " + (locationHandler != null ? "location" : "none"));
        running = true;
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        sensorManager.unregisterListener(this);
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        running = false;
        LOGGER.info("ACTIVITY classifier stopped");
    }

    // ------------------------------------------------------------------------------------------
    // SENSOR EVENTS (handler thread)
    // ------------------------------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();

        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            detectorSteps++;
            return;
        }
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        double x = event.values[0];
        double y = event.values[1];
        double z = event.values[2];
        double magnitude = Math.sqrt(x * x + y * y + z * z);

        // Welford running variance of the magnitude
        sampleCount++;
        double delta = magnitude - mean;
        mean += delta / sampleCount;
        m2 += delta * (magnitude - mean);

        // peak counting fallback for cadence
        runningMean += 0.02 * (magnitude - runningMean);
        double deviation = magnitude - runningMean;
        if (deviation > PEAK_THRESHOLD) {
            if (!aboveThreshold && now - lastPeakMs >= MIN_PEAK_GAP_MS) {
                peakSteps++;
                lastPeakMs = now;
            }
            aboveThreshold = true;
        } else {
            aboveThreshold = false;
        }

        if (now - windowStartMs >= WINDOW_MS) {
            finishWindow(now);
            resetWindow(now);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // not needed
    }

    private void resetWindow(long now) {
        windowStartMs = now;
        sampleCount = 0;
        mean = 0;
        m2 = 0;
        detectorSteps = 0;
        peakSteps = 0;
    }

    // ------------------------------------------------------------------------------------------
    // CLASSIFICATION
    // ------------------------------------------------------------------------------------------

    private void finishWindow(long now) {
        if (sampleCount < MIN_SAMPLES_PER_WINDOW) {
            LOGGER.info("ACTIVITY window skipped: only " + sampleCount + " accelerometer samples");
            return;
        }
        double windowSec = (now - windowStartMs) / 1000.0;
        double sigma = Math.sqrt(m2 / sampleCount);
        int steps = useStepDetector ? detectorSteps : peakSteps;
        double cadence = steps / windowSec;
        Float speed = locationHandler != null ? locationHandler.getRecentSpeedMps(MAX_SPEED_AGE_MS) : null;
        double coverage = Math.min(1.0, sampleCount / (double) EXPECTED_SAMPLES_PER_WINDOW);

        Result result = classify(sigma, cadence, speed);
        // fewer samples than expected means the window is less trustworthy
        int adjusted = (int) Math.round(result.confidence * (0.8 + 0.2 * coverage));
        result = new Result(result.label, adjusted);

        LOGGER.info(String.format("ACTIVITY window: sigma=%.3f cadence=%.2f/s speed=%s -> %s (%d)",
                sigma, cadence, speed == null ? "n/a" : String.format("%.2f", speed),
                result.label, result.confidence));

        recent.addLast(result);
        while (recent.size() > SMOOTHING_WINDOWS) {
            recent.removeFirst();
        }
        publish(smooth(), now);
    }

    /** Rule based classification of a single window. */
    private Result classify(double sigma, double cadence, Float speed) {
        boolean stepping = cadence >= STEP_CADENCE_MIN;
        boolean running = cadence >= RUNNING_CADENCE_MIN;

        if (speed != null) {
            double v = speed;
            if (v < SPEED_STILL) {
                if (sigma < STILL_SIGMA && !stepping) return new Result("still", 90);
                if (stepping) return new Result("walking", 60);   // stepping on the spot / indoors
                return new Result("still", 70);                    // phone being handled
            }
            if (v < SPEED_WALK_MAX) {
                if (stepping) return new Result("walking", 90);    // steps AND walking speed
                if (sigma >= BUMPY_SIGMA) return new Result("bike", 50);
                return new Result("others", 40);                   // slow, no steps: GPS drift or crawling traffic
            }
            if (v < SPEED_RUN_MAX && stepping && running) {
                return new Result("walking", 65);                  // running; the app has no separate label
            }
            if (v >= SPEED_VEHICLE) {
                return new Result("vehicle", 90);
            }
            if (v < SPEED_BIKE_MAX && sigma >= BUMPY_SIGMA) {
                return new Result("bike", 60);
            }
            return new Result("vehicle", v >= 5.0 ? 75 : 60);
        }

        // no recent speed: accelerometer / steps only, so confidence is capped lower
        if (stepping) return new Result("walking", 70);
        if (sigma < STILL_SIGMA) return new Result("still", 65);
        return new Result("others", 35);
    }

    /**
     * Combines the last few windows. The label with the largest total confidence wins and its
     * confidence is averaged over all windows considered, so disagreement lowers it.
     */
    private Result smooth() {
        Map<String, Integer> totals = new HashMap<>();
        for (Result r : recent) {
            totals.merge(r.label, r.confidence, Integer::sum);
        }
        String best = null;
        int bestTotal = -1;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            if (e.getValue() > bestTotal) {
                best = e.getKey();
                bestTotal = e.getValue();
            }
        }
        return new Result(best, bestTotal / recent.size());
    }

    private void publish(Result result, long timestampMs) {
        if (result.label == null || result.confidence < MIN_CONFIDENCE_TO_RECORD) {
            LOGGER.info("ACTIVITY not recorded: " + result.label + " confidence " + result.confidence
                    + " below " + MIN_CONFIDENCE_TO_RECORD);
            return;
        }
        LOGGER.info("ACTIVITY recorded: " + result.label + " confidence " + result.confidence);
        dbExecutor.execute(() -> sensorLocalSource.saveActivityData(result.label, result.confidence, timestampMs));
    }
}
