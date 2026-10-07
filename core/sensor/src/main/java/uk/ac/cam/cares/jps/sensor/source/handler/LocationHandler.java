package uk.ac.cam.cares.jps.sensor.source.handler;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.os.Build;
import androidx.core.content.ContextCompat;


/**
 * Handles location updates and atmospheric pressure readings.
 *
 * HOW RECORDING WORKS (Google fused provider only, NO smoothing):
 *
 *   1. The ONLY location source is Google's fused provider (one-shot request + continuous
 *      updates). The raw GPS provider is not used. The provider is always queried at a fixed
 *      rate (FUSED_INTERVAL_MS = 2 s), regardless of satellite visibility.
 *   2. Every fused fix that passes the accuracy gate becomes the "last good fix".
 *   3. A ticker records the last good fix on every tick. If a new good fix arrived since the
 *      previous tick, that is a fresh point; if not (bad fixes, tunnel, underground), the last
 *      good fix is recorded again (a "held" point), so the track has no gaps.
 *   4. The ticker's interval is ADAPTIVE, driven by the GnssStatus satellite count:
 *        - satellites in use  -> record every RECORD_INTERVAL_NORMAL_MS   (3 s)
 *        - no satellites      -> record every RECORD_INTERVAL_NO_SATS_MS  (2 min)
 *      Going down to the slow rate is debounced (SATS_LOST_DEBOUNCE_MS). Going back up to the
 *      fast rate is immediate: the GnssStatus callback reschedules the ticker the moment
 *      satellites return, so a recovery is never delayed by a pending 2 minute wait.
 *   5. Fixes are recorded exactly as the provider reported them: no filtering, smoothing or
 *      prediction.
 *
 * The GnssStatus callback therefore does two jobs now: diagnostics (satellites used / signal
 * strength) and driving the adaptive record rate.
 */
public class LocationHandler implements SensorHandler, SensorEventListener {
    private Context context;
    private LocationManager locationManager; // used for GnssStatus (diagnostics + adaptive rate)
    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private float currentPressure = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
    private JSONArray locationData;
    private int mslConstant; // Mean sea level pressure constant for altitude calculations
    private Logger LOGGER = Logger.getLogger(LocationHandler.class);
    private boolean isRunning = false;
    private final Object sensorDataLock = new Object(); // Lock object for synchronization

    // ------------------------------------------------------------------------------------------
    // TUNING CONSTANTS
    // ------------------------------------------------------------------------------------------

    // Fused fixes worse than this (metres) are ignored entirely.
    private static final float MAX_ACCEPTABLE_ACCURACY_M = 100f;

    // The very first fix of a session must be this fresh (ms), so a stale cached fix can't start it.
    private static final long MAX_FIRST_FIX_AGE_MS = 5000;

    // A last good fix older than this (ms) is logged as "held" rather than fresh. Logging only;
    // it doesn't change what gets recorded. (Only logged while satellites are in use: with no
    // satellites, a held fix is expected and logging it every tick would be noise.)
    private static final long HELD_AFTER_MS = 5000;

    // How often fused is asked for fixes (ms). FIXED: never changes with satellite state.
    private static final long FUSED_INTERVAL_MS = 2000;

    // How often a point is recorded while satellites are visible (ms).
    private static final long RECORD_INTERVAL_NORMAL_MS = 3000;

    // How often a point is recorded while no satellites are in use (ms) = 2 minutes.
    private static final long RECORD_INTERVAL_NO_SATS_MS = 2 * 60 * 1000;

    // Fewer satellites than this "used in fix" counts as "no satellites".
    private static final int MIN_SATS_USED = 4;

    // Satellites must stay below MIN_SATS_USED this long (ms) before switching to the slow rate.
    // Stops a brief dip (a bridge, a row of tall buildings) from dropping us to 2 minute points.
    private static final long SATS_LOST_DEBOUNCE_MS = 10_000;

    // ------------------------------------------------------------------------------------------
    // STATE (guarded by sensorDataLock)
    // ------------------------------------------------------------------------------------------

    // Most recent fix of any quality (diagnostics only).
    private Location latestLocation;

    // Most recent fix that passed the gates. Re-recorded every tick until a newer one arrives.
    private Location lastGoodFix;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    // === GNSS STATE: updated by the GnssStatus callback ===
    private volatile int satsVisible = -1;
    private volatile int satsUsed = -1;
    private volatile float avgCn0 = -1;
    private boolean gnssCallbackRegistered = false;

    private GnssMeasurementsEvent.Callback gnssMeasurementsCallback;
    private boolean gnssMeasurementsRegistered = false;

    // === ADAPTIVE RATE STATE ===
    // true while we are in the slow (2 min) recording mode.
    // Only touched on the main thread (the ticker and the GnssStatus callback both run on
    // locationRecordHandler), but volatile for safety.
    private volatile boolean noSatsMode = false;
    // elapsedRealtime when satsUsed first dropped below MIN_SATS_USED; 0 = satellites are fine.
    private long noGnssSinceMs = 0;

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int used = 0;
            float cn0Sum = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) {
                    used++;
                    cn0Sum += status.getCn0DbHz(i);
                }
            }
            satsVisible = status.getSatelliteCount();
            satsUsed = used;
            avgCn0 = used > 0 ? cn0Sum / used : 0;

            // FAST RECOVERY: this callback is delivered on locationRecordHandler (see
            // registerGnssStatusCallback in startLocationUpdates), so we are on the same thread
            // as the ticker. If satellites are back while in slow mode, leave slow mode and
            // restart the ticker right now instead of waiting out the remaining 2 minutes.
            if (isRunning && noSatsMode && used >= MIN_SATS_USED) {
                noSatsMode = false;
                noGnssSinceMs = 0;
                LOGGER.info("LOCATION GNSS RECOVERED: satsUsed=" + used + "/" + satsVisible
                        + ", back to " + (RECORD_INTERVAL_NORMAL_MS / 1000) + "s recording");
                locationRecordHandler.removeCallbacks(locationRecordRunnable);
                locationRecordHandler.post(locationRecordRunnable); // records immediately
            }
        }
    };

    /** Age of a fix in ms, on the monotonic clock (immune to wall-clock changes). */
    private long fixAgeMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000;
    }

    /** DIAGNOSTICS: one-line description of a fix's quality, age, and the current GNSS state. */
    private String describeFix(Location l) {
        return "provider=" + l.getProvider()
                + " acc=" + (l.hasAccuracy() ? l.getAccuracy() : -1)
                + " ageMs=" + fixAgeMs(l)
                + " sats=" + satsUsed + "/" + satsVisible
                + " cn0=" + avgCn0;
    }

    // ------------------------------------------------------------------------------------------
    // INCOMING FIXES
    // ------------------------------------------------------------------------------------------

    /**
     * Single entry point for every incoming fused fix.
     *
     * Applies the accuracy gate and the first-fix freshness guard. A fix that passes becomes the
     * new last good fix; one that fails is only logged and the previous good fix stays in place.
     *
     * Safe to call from any thread.
     */
    private void handleIncomingFix(Location location, String source) {
        if (location == null) {
            return;
        }

        boolean good = location.hasAccuracy()
                && location.getAccuracy() <= MAX_ACCEPTABLE_ACCURACY_M;

        String verdict;
        synchronized (sensorDataLock) {
            latestLocation = location;

            if (!good) {
                verdict = "REJECTED(accuracy)";
            } else if (lastGoodFix == null && fixAgeMs(location) > MAX_FIRST_FIX_AGE_MS) {
                verdict = "REJECTED(stale first fix)";
            } else if (satsUsed >= MIN_SATS_USED
                    && location.hasSpeed()
                    && location.getSpeed() < 0.8f
                    && lastGoodFix != null) {
                // Vehicle is effectively stationary: keep the previous good position
                verdict = "REJECTED(STATIONARY)";
            } else {
                lastGoodFix = location;
                verdict = "accepted";
            }
        }

        String line = "LOCATION GATE: " + verdict + " fix from " + source + " " + describeFix(location)
                + " lat=" + location.getLatitude()
                + " lon=" + location.getLongitude()
                + " spd=" + (location.hasSpeed() ? location.getSpeed() : -1)
                + " brg=" + (location.hasBearing() ? location.getBearing() : -1)
                + " tNs=" + location.getElapsedRealtimeNanos();
        if (verdict.equals("accepted")) {
            LOGGER.info(line);
        } else {
            LOGGER.warn(line);
        }
    }

    // ------------------------------------------------------------------------------------------
    // ADAPTIVE TICKER: records one point per tick, tick length depends on satellite visibility
    // ------------------------------------------------------------------------------------------

    private final Handler locationRecordHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable locationRecordRunnable =
            new Runnable() {
                @Override
                public void run() {

                    if (!isRunning) {
                        return;
                    }

                    // --- 1. Decide the mode for the NEXT interval, from the satellite count ---
                    updateRecordingMode();

                    // --- 2. Record the last good fix (always, in both modes) ---
                    Location toRecord;
                    Location rawLatest;

                    synchronized (sensorDataLock) {
                        toRecord = lastGoodFix;
                        rawLatest = latestLocation;
                    }

                    // DIAGNOSTICS: shows the raw latest fused fix, even if it was rejected.
                    LOGGER.info("LOCATION DIAG tick: "
                            + (rawLatest != null ? describeFix(rawLatest) : "no fix yet")
                            + " mode=" + (noSatsMode ? "NO_SATS" : "NORMAL"));

                    if (toRecord != null) {
                        long age = fixAgeMs(toRecord);
                        if (!noSatsMode && age > HELD_AFTER_MS) {
                            LOGGER.warn("LOCATION: no new good fix for " + (age / 1000)
                                    + "s, re-recording last good fix");
                        }
                        recordLocation(toRecord);
                    } else {
                        LOGGER.warn("LOCATION: no good fix yet since session start, nothing to record");
                    }

                    // --- 3. Schedule the next tick at the rate for the current mode ---
                    locationRecordHandler.postDelayed(this,
                            noSatsMode ? RECORD_INTERVAL_NO_SATS_MS : RECORD_INTERVAL_NORMAL_MS);
                }
            };

    /**
     * Switches into the slow (no satellites) mode once satellites have been missing for
     * SATS_LOST_DEBOUNCE_MS. Switching back is done by the GnssStatus callback so it is instant.
     *
     * If the GnssStatus callback is not registered (e.g. fine location permission missing) we
     * have no satellite information at all, so we stay in normal mode rather than slowing down
     * on the strength of a satsUsed value that will never update.
     */
    private void updateRecordingMode() {
        if (!gnssCallbackRegistered) {
            return;
        }

        long nowMs = SystemClock.elapsedRealtime();

        if (satsUsed >= MIN_SATS_USED) {
            noGnssSinceMs = 0;
            return;
        }

        if (noGnssSinceMs == 0) {
            noGnssSinceMs = nowMs;
        }

        long lostForMs = nowMs - noGnssSinceMs;
        if (!noSatsMode && lostForMs >= SATS_LOST_DEBOUNCE_MS) {
            noSatsMode = true;
            LOGGER.error("LOCATION GNSS ALERT: satsUsed=" + satsUsed + "/" + satsVisible
                    + " for " + (lostForMs / 1000) + "s, fixes are probably network-derived. "
                    + "Switching to " + (RECORD_INTERVAL_NO_SATS_MS / 1000) + "s recording.");
        }
    }

    /**
     * Constructs a LocationHandler with a specified context and initializes location and pressure sensors.
     *
     * @param context The application context used for accessing system services.
     */
    public LocationHandler(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        this.locationData = new JSONArray();
        this.mslConstant = 1006;
    }

    private boolean hasFineLocationPermission() {
        return ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAnyLocationPermission() {
        return hasFineLocationPermission()
                || ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------------------------------
    // FUSED LOCATION PROVIDER
    // ------------------------------------------------------------------------------------------

    /**
     * One-shot request, used to get a quick first fix. maxUpdateAge is 0, so a cached fix is never
     * accepted; the first-fix freshness guard in handleIncomingFix() is a second line of defence.
     */
    private void requestCurrentLocation() {

        if (!hasAnyLocationPermission()) {
            LOGGER.warn("LOCATION: permission not granted");
            return;
        }

        CurrentLocationRequest request =
                new CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0)
                        .setDurationMillis(30_000)
                        .build();

        LOGGER.info("LOCATION: requesting fresh fused location");

        fusedLocationClient.getCurrentLocation(
                request,
                null
        ).addOnSuccessListener(location -> {

            if (location == null) {
                LOGGER.warn("LOCATION: fused getCurrentLocation returned NULL");
                return;
            }

            handleIncomingFix(location, "fused-current");

        }).addOnFailureListener(e -> {
            LOGGER.error("LOCATION: fused getCurrentLocation failed", e);
        });
    }

    /**
     * Continuous fused updates: the main stream of fixes, always at FUSED_INTERVAL_MS (2 s).
     * The interval is deliberately NOT adapted to satellite visibility, so this is requested
     * once per session and never restarted.
     */
    private void startFusedLocationUpdates() {

        if (!hasAnyLocationPermission()) {
            LOGGER.warn("LOCATION: permission not granted");
            return;
        }

        LocationRequest locationRequest =
                new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FUSED_INTERVAL_MS)
                        .setMinUpdateIntervalMillis(FUSED_INTERVAL_MS)
                        .build();

        locationCallback = new LocationCallback() {

            @Override
            public void onLocationResult(LocationResult locationResult) {

                if (locationResult == null) {
                    return;
                }

                for (Location location : locationResult.getLocations()) {
                    if (location == null) {
                        continue;
                    }
                    handleIncomingFix(location, "fused-updates");
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
        );

        LOGGER.info("LOCATION: continuous fused updates started, interval=" + FUSED_INTERVAL_MS + "ms");
    }

    // ------------------------------------------------------------------------------------------
    // START / STOP
    // ------------------------------------------------------------------------------------------

    /**
     * Starts location and pressure data updates. Requires location permission to function properly.
     */
    @Override
    public void start() {
        startLocationUpdates();
    }

    // Overloaded start method that takes an Integer parameter
    public void start(Integer integer) {
        startLocationUpdates();
    }

    private void startLocationUpdates() {

        LOGGER.info("LOCATION: startLocationUpdates() called");

        if (isRunning) {
            LOGGER.info("LOCATION: already running — ignoring duplicate start (likely a resumed session)");
            return;
        }

        if (!hasAnyLocationPermission()) {
            LOGGER.warn("LOCATION: permission not granted");
            return;
        }

        isRunning = true;

        // Start every session from scratch, so nothing from a previous session can be recorded
        // as if it were current.
        synchronized (sensorDataLock) {
            latestLocation = null;
            lastGoodFix = null;
        }
        noGnssSinceMs = 0;
        noSatsMode = false; // every session starts in normal (3 s) mode

        LOGGER.info("LOCATION: handler is now running");

        // Satellite count / signal strength. Requires ACCESS_FINE_LOCATION.
        // The callback is delivered on locationRecordHandler (main looper), the same thread as
        // the ticker, so the mode flag is never touched from two threads at once.
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, locationRecordHandler);
            gnssCallbackRegistered = true;
        } catch (SecurityException e) {
            LOGGER.warn("LOCATION DIAG: could not register GnssStatus callback (fine location missing?)", e);
        }

        startFullTrackingGnss();
        requestCurrentLocation();
        startFusedLocationUpdates();

        locationRecordHandler.post(locationRecordRunnable);

        if (pressureSensor != null) {
            sensorManager.registerListener(
                    this,
                    pressureSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
            );
        }
    }

    private void startFullTrackingGnss() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            LOGGER.warn("LOCATION: full GNSS tracking needs API 31+, GNSS may be duty-cycled");
            return;
        }
        if (!hasFineLocationPermission() || gnssMeasurementsRegistered) {
            return;
        }

        gnssMeasurementsCallback = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                // Intentionally empty. Registering with full tracking is the point.
            }
        };

        GnssMeasurementRequest request = new GnssMeasurementRequest.Builder()
                .setFullTracking(true)
                .build();

        try {
            gnssMeasurementsRegistered = locationManager.registerGnssMeasurementsCallback(
                    request,
                    ContextCompat.getMainExecutor(context),
                    gnssMeasurementsCallback);
            LOGGER.info("LOCATION: full-tracking GNSS measurements registered=" + gnssMeasurementsRegistered);
        } catch (SecurityException e) {
            LOGGER.warn("LOCATION: could not register GNSS measurements", e);
        }
    }

    private void stopFullTrackingGnss() {
        if (gnssMeasurementsRegistered && gnssMeasurementsCallback != null) {
            locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback);
        }
        gnssMeasurementsRegistered = false;
        gnssMeasurementsCallback = null;
    }


    /**
     * Stops location and pressure data updates.
     */
    @Override
    public void stop() {
        isRunning = false;

        sensorManager.unregisterListener(this);

        locationRecordHandler.removeCallbacks(locationRecordRunnable);

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }

        // Stop GNSS status updates and reset the cached values and adaptive-rate state
        if (gnssCallbackRegistered) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssCallbackRegistered = false;
        }
        stopFullTrackingGnss();
        satsVisible = -1;
        satsUsed = -1;
        avgCn0 = -1;
        noSatsMode = false;
        noGnssSinceMs = 0;

        synchronized (sensorDataLock) {
            latestLocation = null;
            lastGoodFix = null;   // forget the track when the session ends
        }

        LOGGER.info("LOCATION: handler stopped");
    }


    /**
     * Callback for sensor data changes, specifically for the atmospheric pressure sensor.
     *
     * @param event The sensor event containing the new readings.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0];
        }
    }

    // ------------------------------------------------------------------------------------------
    // RECORDING
    // ------------------------------------------------------------------------------------------

    /**
     * Adds one point to the memory buffer, exactly as the fused provider reported it. The same
     * Location may be recorded on several ticks in a row when no newer good fix exists (a "held"
     * point); each record still gets its own "time".
     */
    private void recordLocation(Location loc) {
        LOGGER.info("LOCATION RECORD: adding location to memory buffer");

        LOGGER.info(
                "LOCATION RECORD: lat=" + loc.getLatitude()
                        + ", lon=" + loc.getLongitude()
                        + ", accuracy=" + (loc.hasAccuracy() ? loc.getAccuracy() : -1)
                        + ", speed=" + (loc.hasSpeed() ? loc.getSpeed() : -1)
                        + ", bearing=" + (loc.hasBearing() ? loc.getBearing() : -1)
        );

        synchronized (sensorDataLock) {
            double altitude = SensorManager.getAltitude(mslConstant, currentPressure);

            try {
                JSONObject locationObject = new JSONObject();
                locationObject.put("name", "location");
                // "time" is the RECORDING time (now), not the time of the fix.
                locationObject.put("time", System.currentTimeMillis());
                JSONObject values = new JSONObject();
                values.put("latitude", loc.getLatitude());
                values.put("longitude", loc.getLongitude());
                values.put("altitude", altitude);
                values.put("speed", loc.hasSpeed() ? loc.getSpeed() : 0.0);
                values.put("bearing", loc.hasBearing() ? loc.getBearing() : 0.0);
                values.put("horizontalAccuracy", loc.getAccuracy());

                // Optional keys are only written when the fix has them. Ingestion should tolerate
                // their absence.
                if (loc.hasSpeedAccuracy()) {
                    values.put("speedAccuracy", loc.getSpeedAccuracyMetersPerSecond());
                }
                if (loc.hasBearingAccuracy()) {
                    values.put("bearingAccuracy", loc.getBearingAccuracyDegrees());
                }
                if (loc.hasVerticalAccuracy()) {
                    values.put("verticalAccuracy", loc.getVerticalAccuracyMeters());
                }

                // OPTIONAL DIAGNOSTICS in the persisted data. Uncomment only if your backend /
                // knowledge-graph ingestion tolerates extra keys.
                // values.put("satsUsed", satsUsed);
                // values.put("satsVisible", satsVisible);
                // values.put("avgCn0", avgCn0);
                // values.put("noSatsMode", noSatsMode);

                locationObject.put("values", values);
                locationData.put(locationObject);

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Clears the stored sensor data.
     */
    @Override
    public void clearSensorData() {
        LOGGER.info("LOCATION DATA CLEARED: memory buffer flushed");
        synchronized (sensorDataLock) {
            locationData = new JSONArray();
        }
    }

    /**
     * Retrieves the collected location data.
     *
     * @return A {@link JSONArray} containing structured JSON objects of the location data.
     */
    @Override
    public JSONArray getSensorData() {
        LOGGER.info(
                "LOCATION DATA READ: returning "
                        + locationData.length()
                        + " records"
        );

        synchronized (sensorDataLock) {
            try {
                return new JSONArray(locationData.toString());
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed to implement
    }

    @Override
    public String getSensorName() {
        return "location";
    }

    @Override
    public Boolean isRunning() {
        return isRunning;
    }

    @Override
    public SensorType getSensorType() {
        return SensorType.LOCATION;
    }

    @Override
    public Object getSensorDataLock() {
        return sensorDataLock;
    }

}