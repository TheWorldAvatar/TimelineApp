package uk.ac.cam.cares.jps.sensor.source.handler;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationRequest;





/**
 * Handles location updates and atmospheric pressure readings. This class integrates with both the
 * LocationManager for location updates and the SensorHandlerManager for pressure data, providing comprehensive
 * environmental data through a unified interface.
 *
 * It provides functionality to start and stop location and pressure monitoring, handle changes, and
 * manage the collected data in a structured JSON format.
 */
public class LocationHandler implements LocationListener, SensorHandler, SensorEventListener {
    private Context context;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private float currentPressure = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
    private JSONArray locationData;
    private int mslConstant; // Mean sea level pressure constant for altitude calculations
    private Logger LOGGER = Logger.getLogger(LocationHandler.class);
    private boolean isRunning = false;
    private final Object sensorDataLock = new Object(); // Lock object for synchronization

    private Location latestLocation;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;


    private final Handler locationRecordHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable locationRecordRunnable =
            new Runnable() {
                @Override
                public void run() {

                    if (!isRunning) {
                        return;
                    }

                    Location location;

                    synchronized (sensorDataLock) {
                        location = latestLocation;
                    }

                    if (location != null) {
                        recordLocation(location);
                    } else {
                        LOGGER.warn(
                                "LOCATION: no location available to record"
                        );
                    }

                    locationRecordHandler.postDelayed(
                            this,
                            2000
                    );
                }
            };

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

    

    private void requestCurrentLocation() {

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            LOGGER.warn("LOCATION: permission not granted");
            return;
        }

        CurrentLocationRequest request =
                new CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0)
                        .setDurationMillis(30_000)
                        .build();

        LOGGER.info(
                "LOCATION: requesting fresh fused location"
        );

        fusedLocationClient.getCurrentLocation(
                request,
                null
        ).addOnSuccessListener(location -> {

            if (location == null) {

                LOGGER.warn(
                        "LOCATION: fused getCurrentLocation returned NULL"
                );

                return;
            }

            LOGGER.info(
                    "LOCATION: FRESH FUSED FIX:"
                            + " lat=" + location.getLatitude()
                            + ", lon=" + location.getLongitude()
                            + ", accuracy=" + location.getAccuracy()
                            + ", provider=" + location.getProvider()
                            + ", time=" + location.getTime()
            );

            synchronized (sensorDataLock) {
                latestLocation = location;
            }

            locationRecordHandler.post(locationRecordRunnable);

        }).addOnFailureListener(e -> {

            LOGGER.error(
                    "LOCATION: fused getCurrentLocation failed",
                    e
            );
        });
    }

    private void startFusedLocationUpdates() {

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            LOGGER.warn("LOCATION: permission not granted");
            return;
        }

        LocationRequest locationRequest =
                LocationRequest.create()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setInterval(2000)
                        .setFastestInterval(1000);

        locationCallback = new LocationCallback() {

            @Override
            public void onLocationResult(
                    LocationResult locationResult) {

                if (locationResult == null) {
                    return;
                }

                for (Location location :
                        locationResult.getLocations()) {

                    if (location == null) {
                        continue;
                    }

                    synchronized (sensorDataLock) {
                        latestLocation = location;
                    }

                    LOGGER.info(
                            "LOCATION: fused update:"
                                    + " lat=" + location.getLatitude()
                                    + ", lon=" + location.getLongitude()
                                    + ", accuracy=" + location.getAccuracy()
                                    + ", speed=" + location.getSpeed()
                                    + ", time=" + location.getTime()
                    );
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
        );

        LOGGER.info(
                "LOCATION: continuous fused updates started"
        );
    }


    /**
     * Starts location and pressure data updates. Requires fine location permission to function properly.
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

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            LOGGER.warn("LOCATION: permission not granted");
            return;
        }

        isRunning = true;

        LOGGER.info("LOCATION: handler is now running");

        // Ask Google Fused Location for a fresh location.
        requestCurrentLocation();

        // Keep receiving location updates afterwards.
        startFusedLocationUpdates();

        if (pressureSensor != null) {
            sensorManager.registerListener(
                    this,
                    pressureSensor,
                    SensorManager.SENSOR_DELAY_NORMAL
            );
        }
    }


    /**
     * Stops location and pressure data updates.
     */
    @Override
    public void stop() {
        isRunning = false;

        locationManager.removeUpdates(this);
        sensorManager.unregisterListener(this);

        locationRecordHandler.removeCallbacks(locationRecordRunnable);

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }

        synchronized (sensorDataLock) {
            latestLocation = null;
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

    /**
     * Callback for location changes, which includes calculation of altitude using atmospheric pressure.
     *
     * @param location The new location object containing updated latitude, longitude, and other data.
     */

    
    @Override
    public void onLocationChanged(Location location) {

        if (!isRunning) {
            return;
        }

        synchronized (sensorDataLock) {
            latestLocation = location;
        }

        LOGGER.info(
                "LOCATION: new location update:"
                        + " lat=" + location.getLatitude()
                        + ", lon=" + location.getLongitude()
                        + ", accuracy=" + location.getAccuracy()
                        + ", speed=" + location.getSpeed()
                        + ", time=" + location.getTime()
        );
    }

    

    private void recordLocation(Location location) {
        // your existing JSON creation code
        LOGGER.info(
                "LOCATION RECORD: adding location to memory buffer"
        );

        LOGGER.info(
            "LOCATION RECORD: lat=" + location.getLatitude()
            + ", lon=" + location.getLongitude()
            + ", accuracy=" + location.getAccuracy()
            + ", speed=" + location.getSpeed()
            + ", gpsTime=" + location.getTime()
        );


        synchronized (sensorDataLock) {
            double altitude = SensorManager.getAltitude(mslConstant, currentPressure);

            try {
                JSONObject locationObject = new JSONObject();
                locationObject.put("name", "location");
                locationObject.put("time", System.currentTimeMillis());
                JSONObject values = new JSONObject();
                values.put("latitude", location.getLatitude());
                values.put("longitude", location.getLongitude());
                values.put("altitude", altitude);
                values.put("speed", location.getSpeed());
                values.put("bearing", location.getBearing());
                values.put("horizontalAccuracy", location.hasAccuracy() ? location.getAccuracy() : null);
                values.put("bearingAccuracy", location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : null);
                values.put("speedAccuracy", location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : null);
                values.put("verticalAccuracy", location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : null);

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
        LOGGER.info(
                "LOCATION DATA CLEARED: memory buffer flushed"
        );
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

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        LOGGER.info("Location provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        LOGGER.info("Location provider disabled: " + provider);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        String statusMessage;
        switch (status) {
            case LocationProvider.OUT_OF_SERVICE:
                statusMessage = "Provider out of service";
                break;
            case LocationProvider.TEMPORARILY_UNAVAILABLE:
                statusMessage = "Provider temporarily unavailable";
                break;
            case LocationProvider.AVAILABLE:
                statusMessage = "Provider available";
                break;
            default:
                statusMessage = "Provider status changed: " + status;
                break;
        }
        LOGGER.info("Location provider " + provider + ": " + statusMessage);
    }

}
