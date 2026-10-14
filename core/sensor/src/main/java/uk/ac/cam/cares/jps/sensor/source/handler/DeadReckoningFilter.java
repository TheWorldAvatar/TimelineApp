package uk.ac.cam.cares.jps.sensor.source.handler;

import java.util.Arrays;

/**
 * Constant-velocity Kalman filter that turns a stream of noisy location fixes into a smoothed
 * track, and keeps extrapolating ("dead reckoning") when fixes stop arriving.
 *
 * STATE (in a local east/north frame, metres, anchored at the first fix):
 *     x = [ east, north, velEast, velNorth ]
 *
 * HOW IT WORKS
 *   - predict: between fixes the position advances by velocity * dt. Uncertainty grows with dt
 *     (process noise = random acceleration with std dev accelSigma).
 *   - position update: each fix pulls the state towards the measured position, weighted by the
 *     fix's reported horizontal accuracy (a 5 m fix moves the estimate a lot, a 60 m fix barely).
 *   - velocity update: the fix's speed + bearing give a velocity measurement. When speed is very
 *     low the velocity is pulled towards zero, which stops the track drifting while standing still.
 *   - outlier gate: a fix far outside what the filter expects (chi-square test on the innovation)
 *     is rejected. After MAX_CONSECUTIVE_REJECTS in a row the filter re-initialises on the new fix,
 *     so a genuine jump (e.g. long gap) doesn't lock the filter out.
 *
 * LIMITS: constant-velocity extrapolation is good for tunnels / short dropouts on straight roads.
 * It cannot know about turns or stops while no fixes arrive, so error grows quickly after ~15-30 s.
 * Adding gyroscope yaw rate would help with turns (not included here).
 *
 * This class has NO Android dependencies, so it can be unit-tested or replayed offline against
 * logged fixes. It is NOT thread-safe: callers synchronise (LocationHandler uses sensorDataLock).
 */
public class DeadReckoningFilter {

    /** A smoothed / extrapolated position at a given moment. */
    public static final class Estimate {
        public final double latitude;
        public final double longitude;
        public final double speedMps;
        public final double bearingDeg;            // 0 = north, clockwise, [0, 360)
        public final double horizontalAccuracyM;   // approx. 68% radius, from the filter covariance
        public final double speedAccuracyMps;
        public final long msSinceLastPositionUpdate;

        Estimate(double latitude, double longitude, double speedMps, double bearingDeg,
                 double horizontalAccuracyM, double speedAccuracyMps, long msSinceLastPositionUpdate) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedMps = speedMps;
            this.bearingDeg = bearingDeg;
            this.horizontalAccuracyM = horizontalAccuracyM;
            this.speedAccuracyMps = speedAccuracyMps;
            this.msSinceLastPositionUpdate = msSinceLastPositionUpdate;
        }
    }

    // ---------------------------------------------------------------- tuning constants
    private static final double METERS_PER_DEG_LAT = 111_194.9;  // pi/180 * 6,371,000
    private static final double MIN_POSITION_SIGMA_M = 3.0;      // never trust a fix more than this
    private static final double MOVING_SPEED_MPS = 0.8;          // below this: treat as stationary
    private static final double STATIONARY_SIGMA_MPS = 0.7;
    private static final double DEFAULT_SPEED_SIGMA_MPS = 1.0;   // used if the fix has no speed accuracy
    private static final double DEFAULT_BEARING_SIGMA_DEG = 20.0;
    private static final double MIN_VELOCITY_SIGMA_MPS = 0.5;
    private static final double UNKNOWN_VELOCITY_SIGMA_MPS = 10.0;
    private static final double GATE_CHI2 = 13.8;                // 2 dof, ~99.9%
    private static final int MAX_CONSECUTIVE_REJECTS = 4;
    private static final double REBASE_DISTANCE_M = 5000.0;      // re-anchor the local frame beyond this

    // ---------------------------------------------------------------- state
    private final double accelSigma;     // m/s^2, how much the speed/heading may change unannounced
    private boolean initialised = false;
    private double lat0, lon0, metersPerDegLon;
    private final double[] x = new double[4];
    private final double[][] P = new double[4][4];
    private long stateTimeNanos;
    private long lastPositionUpdateNanos;
    private int consecutiveRejects = 0;
    private double lastBearingDeg = 0.0;

    /**
     * @param accelSigmaMps2 expected random acceleration (m/s^2). ~1.0 for walking, ~1.5-2.5 for
     *                       a car. Higher = trusts new fixes more / extrapolates less stiffly.
     */
    public DeadReckoningFilter(double accelSigmaMps2) {
        this.accelSigma = accelSigmaMps2;
    }

    public void reset() {
        initialised = false;
        consecutiveRejects = 0;
    }

    public boolean isInitialised() {
        return initialised;
    }

    // ================================================================ public API

    /**
     * Feeds one fix into the filter.
     *
     * @param speedAccMps   pass a value <= 0 if unknown
     * @param bearingAccDeg pass a value <= 0 if unknown
     * @param timeNanos     monotonic time of the fix (Location.getElapsedRealtimeNanos())
     * @return true if the fix was used, false if it was rejected (outlier or older than the state)
     */
    public boolean processFix(double lat, double lon, double accuracyM,
                              boolean hasSpeed, double speedMps, double speedAccMps,
                              boolean hasBearing, double bearingDeg, double bearingAccDeg,
                              long timeNanos) {

        double sigmaPos = Math.max(accuracyM, MIN_POSITION_SIGMA_M);

        if (!initialised) {
            initialise(lat, lon, sigmaPos, hasSpeed, speedMps, speedAccMps,
                    hasBearing, bearingDeg, bearingAccDeg, timeNanos);
            return true;
        }

        if (timeNanos < stateTimeNanos) {
            return false; // older than what we've already absorbed
        }
        if (timeNanos > stateTimeNanos) {
            predict(x, P, (timeNanos - stateTimeNanos) / 1e9, accelSigma);
            stateTimeNanos = timeNanos;
        }

        double e = (lon - lon0) * metersPerDegLon;
        double n = (lat - lat0) * METERS_PER_DEG_LAT;
        double r2 = sigmaPos * sigmaPos;

        if (innovationChi2(0, e, n, r2) > GATE_CHI2) {
            consecutiveRejects++;
            if (consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) {
                initialise(lat, lon, sigmaPos, hasSpeed, speedMps, speedAccMps,
                        hasBearing, bearingDeg, bearingAccDeg, timeNanos);
                return true;
            }
            return false;
        }
        consecutiveRejects = 0;

        update(0, e, n, r2);
        lastPositionUpdateNanos = timeNanos;

        double[] v = velocityMeasurement(hasSpeed, speedMps, speedAccMps,
                hasBearing, bearingDeg, bearingAccDeg);
        if (v != null) {
            update(2, v[0], v[1], v[2] * v[2]);
        }

        rebaseIfFar();
        return true;
    }

    /**
     * Best estimate at time {@code timeNanos}. Does not modify the filter (it extrapolates a copy),
     * so it is safe to call every tick.
     *
     * @param maxDeadReckonMs give up (return null) if no fix has been absorbed for this long
     * @return the estimate, or null if the filter is uninitialised or has been without fixes too long
     */
    public Estimate estimateAt(long timeNanos, long maxDeadReckonMs) {
        if (!initialised) {
            return null;
        }
        long sinceMs = Math.max(0L, (timeNanos - lastPositionUpdateNanos) / 1_000_000L);
        if (sinceMs > maxDeadReckonMs) {
            return null;
        }

        double[] xs = x.clone();
        double[][] Ps = copy(P);
        if (timeNanos > stateTimeNanos) {
            predict(xs, Ps, (timeNanos - stateTimeNanos) / 1e9, accelSigma);
        }

        double lat = lat0 + xs[1] / METERS_PER_DEG_LAT;
        double lon = lon0 + xs[0] / metersPerDegLon;
        double speed = Math.hypot(xs[2], xs[3]);
        double bearing = speed >= MOVING_SPEED_MPS
                ? normaliseDeg(Math.toDegrees(Math.atan2(xs[2], xs[3])))
                : lastBearingDeg;

        // 68% radius of an isotropic 2-D Gaussian is ~1.5 sigma per axis.
        double accuracy = 1.5 * Math.sqrt((Ps[0][0] + Ps[1][1]) / 2.0);
        double speedAcc = Math.sqrt((Ps[2][2] + Ps[3][3]) / 2.0);

        return new Estimate(lat, lon, speed, bearing, accuracy, speedAcc, sinceMs);
    }

    // ================================================================ internals

    private void initialise(double lat, double lon, double sigmaPos,
                            boolean hasSpeed, double speedMps, double speedAccMps,
                            boolean hasBearing, double bearingDeg, double bearingAccDeg,
                            long timeNanos) {
        lat0 = lat;
        lon0 = lon;
        metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));

        for (double[] row : P) {
            Arrays.fill(row, 0.0);
        }
        x[0] = 0.0;
        x[1] = 0.0;
        P[0][0] = sigmaPos * sigmaPos;
        P[1][1] = sigmaPos * sigmaPos;

        double[] v = velocityMeasurement(hasSpeed, speedMps, speedAccMps,
                hasBearing, bearingDeg, bearingAccDeg);
        if (v != null) {
            x[2] = v[0];
            x[3] = v[1];
            P[2][2] = v[2] * v[2];
            P[3][3] = v[2] * v[2];
        } else {
            x[2] = 0.0;
            x[3] = 0.0;
            P[2][2] = UNKNOWN_VELOCITY_SIGMA_MPS * UNKNOWN_VELOCITY_SIGMA_MPS;
            P[3][3] = UNKNOWN_VELOCITY_SIGMA_MPS * UNKNOWN_VELOCITY_SIGMA_MPS;
        }

        stateTimeNanos = timeNanos;
        lastPositionUpdateNanos = timeNanos;
        consecutiveRejects = 0;
        initialised = true;
    }

    /**
     * Converts a fix's speed/bearing into {velEast, velNorth, sigma}, or null if the fix carries no
     * usable velocity. Also remembers the bearing while moving (used for display when stopped).
     */
    private double[] velocityMeasurement(boolean hasSpeed, double speed, double speedAcc,
                                         boolean hasBearing, double bearingDeg, double bearingAccDeg) {
        if (!hasSpeed) {
            return null;
        }
        if (speed < MOVING_SPEED_MPS) {
            return new double[]{0.0, 0.0, STATIONARY_SIGMA_MPS};
        }
        if (!hasBearing) {
            return null;
        }
        double sA = speedAcc > 0 ? speedAcc : DEFAULT_SPEED_SIGMA_MPS;
        double bA = Math.toRadians(bearingAccDeg > 0 ? bearingAccDeg : DEFAULT_BEARING_SIGMA_DEG);
        double sigma = Math.max(MIN_VELOCITY_SIGMA_MPS, Math.hypot(sA, speed * bA));
        double b = Math.toRadians(bearingDeg);
        lastBearingDeg = normaliseDeg(bearingDeg);
        return new double[]{speed * Math.sin(b), speed * Math.cos(b), sigma};
    }

    /** Moves the local frame's origin to the current estimate so the flat-earth approximation stays valid. */
    private void rebaseIfFar() {
        if (Math.hypot(x[0], x[1]) <= REBASE_DISTANCE_M) {
            return;
        }
        lat0 = lat0 + x[1] / METERS_PER_DEG_LAT;
        lon0 = lon0 + x[0] / metersPerDegLon;
        metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat0));
        x[0] = 0.0;
        x[1] = 0.0;
    }

    private static double normaliseDeg(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    // ---------------------------------------------------------------- Kalman maths

    /** x <- F x ;  P <- F P F^T + Q  (constant-velocity model, random-acceleration noise). */
    private static void predict(double[] x, double[][] P, double dt, double sigmaA) {
        x[0] += x[2] * dt;
        x[1] += x[3] * dt;

        double[][] F = {
                {1, 0, dt, 0},
                {0, 1, 0, dt},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };
        double[][] FPFt = mulByTranspose(mul(F, P), F);

        double s2 = sigmaA * sigmaA;
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;
        double dt4 = dt3 * dt;
        double q00 = s2 * dt4 / 4.0;
        double q02 = s2 * dt3 / 2.0;
        double q22 = s2 * dt2;

        FPFt[0][0] += q00;
        FPFt[1][1] += q00;
        FPFt[0][2] += q02;
        FPFt[2][0] += q02;
        FPFt[1][3] += q02;
        FPFt[3][1] += q02;
        FPFt[2][2] += q22;
        FPFt[3][3] += q22;

        for (int i = 0; i < 4; i++) {
            System.arraycopy(FPFt[i], 0, P[i], 0, 4);
        }
    }

    /**
     * Measurement update for a 2-D measurement of state components (o, o+1):
     * o = 0 for position, o = 2 for velocity. r2 = measurement variance per axis.
     */
    private void update(int o, double zx, double zy, double r2) {
        double s00 = P[o][o] + r2;
        double s01 = P[o][o + 1];
        double s10 = P[o + 1][o];
        double s11 = P[o + 1][o + 1] + r2;
        double det = s00 * s11 - s01 * s10;
        if (Math.abs(det) < 1e-12) {
            return;
        }
        double i00 = s11 / det;
        double i01 = -s01 / det;
        double i10 = -s10 / det;
        double i11 = s00 / det;

        double y0 = zx - x[o];
        double y1 = zy - x[o + 1];

        double[][] K = new double[4][2];
        for (int i = 0; i < 4; i++) {
            K[i][0] = P[i][o] * i00 + P[i][o + 1] * i10;
            K[i][1] = P[i][o] * i01 + P[i][o + 1] * i11;
        }

        double[] row0 = P[o].clone();
        double[] row1 = P[o + 1].clone();

        for (int i = 0; i < 4; i++) {
            x[i] += K[i][0] * y0 + K[i][1] * y1;
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                P[i][j] -= K[i][0] * row0[j] + K[i][1] * row1[j];
            }
        }
        // keep P symmetric against rounding drift
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                double avg = 0.5 * (P[i][j] + P[j][i]);
                P[i][j] = avg;
                P[j][i] = avg;
            }
        }
    }

    /** Mahalanobis distance^2 of a candidate measurement from the current prediction. */
    private double innovationChi2(int o, double zx, double zy, double r2) {
        double s00 = P[o][o] + r2;
        double s01 = P[o][o + 1];
        double s10 = P[o + 1][o];
        double s11 = P[o + 1][o + 1] + r2;
        double det = s00 * s11 - s01 * s10;
        if (Math.abs(det) < 1e-12) {
            return 0.0;
        }
        double y0 = zx - x[o];
        double y1 = zy - x[o + 1];
        double i00 = s11 / det;
        double i01 = -s01 / det;
        double i10 = -s10 / det;
        double i11 = s00 / det;
        return y0 * (i00 * y0 + i01 * y1) + y1 * (i10 * y0 + i11 * y1);
    }

    // ---------------------------------------------------------------- tiny 4x4 helpers

    private static double[][] mul(double[][] A, double[][] B) {
        double[][] C = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += A[i][k] * B[k][j];
                }
                C[i][j] = s;
            }
        }
        return C;
    }

    /** A * B^T */
    private static double[][] mulByTranspose(double[][] A, double[][] B) {
        double[][] C = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += A[i][k] * B[j][k];
                }
                C[i][j] = s;
            }
        }
        return C;
    }

    private static double[][] copy(double[][] A) {
        double[][] C = new double[4][];
        for (int i = 0; i < 4; i++) {
            C[i] = A[i].clone();
        }
        return C;
    }
}
