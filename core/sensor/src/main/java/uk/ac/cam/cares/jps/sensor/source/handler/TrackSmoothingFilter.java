package uk.ac.cam.cares.jps.sensor.source.handler;

import java.util.Arrays;

/**
 * Constant-velocity Kalman filter that turns a stream of noisy location fixes into a smoothed
 * track. It SMOOTHS only: it never invents positions. A smoothed point exists only when a real
 * fix has just been absorbed.
 *
 * STATE (in a local east/north frame, metres, anchored at the first fix):
 *     x = [ east, north, velEast, velNorth ]
 *
 * HOW IT WORKS
 *   - predict: between fixes the filter works out where you most likely are NOW from the last
 *     position and velocity (uncertainty grows with dt; process noise = random acceleration with
 *     std dev accelSigma). This prediction is used ONLY to judge and blend the next fix. It is
 *     never output.
 *   - gyro (optional): yaw rate from the phone's gyroscope (turn rate about the vertical axis) is
 *     integrated and used to rotate the velocity vector inside the predict step, so the prior
 *     follows turns instead of assuming a straight line. It only sharpens the prior used to blend
 *     the next fix; it never creates output on its own.
 *   - position update: each fix pulls the state towards the measured position, weighted by the
 *     fix's reported horizontal accuracy (a 5 m fix moves the estimate a lot, a 60 m fix barely).
 *   - velocity update: the fix's speed + bearing give a velocity measurement. When speed is very
 *     low the velocity is pulled towards zero, which stops the track drifting while standing still.
 *   - outlier gate: a fix far outside what the filter expects (chi-square test on the innovation)
 *     is rejected. After MAX_CONSECUTIVE_REJECTS in a row the filter re-initialises on the new fix,
 *     so a genuine jump (e.g. long gap) doesn't lock the filter out.
 *
 * OUTPUT: latestSmoothed() returns the estimate as of the last accepted fix (no extrapolation).
 * If fixes stop arriving, smoothedCount() stops changing, so callers know there is nothing new
 * to record.
 *
 * This class has NO Android dependencies, so it can be unit-tested or replayed offline against
 * logged fixes. It is NOT thread-safe: callers synchronise (LocationHandler uses sensorDataLock).
 */
public class TrackSmoothingFilter {

    /** A smoothed position, as of the moment a real fix was absorbed. */
    public static final class Estimate {
        public final double latitude;
        public final double longitude;
        public final double speedMps;
        public final double bearingDeg;            // 0 = north, clockwise, [0, 360)
        public final double horizontalAccuracyM;   // approx. 68% radius, from the filter covariance
        public final double speedAccuracyMps;
        public final long fixTimeNanos;            // monotonic time of the fix this is based on

        Estimate(double latitude, double longitude, double speedMps, double bearingDeg,
                 double horizontalAccuracyM, double speedAccuracyMps, long fixTimeNanos) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedMps = speedMps;
            this.bearingDeg = bearingDeg;
            this.horizontalAccuracyM = horizontalAccuracyM;
            this.speedAccuracyMps = speedAccuracyMps;
            this.fixTimeNanos = fixTimeNanos;
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
    private static final int MAX_CONSECUTIVE_REJECTS = 2;
    private static final double REBASE_DISTANCE_M = 5000.0;      // re-anchor the local frame beyond this

    // ---------------------------------------------------------------- gyro tuning
    // 1-sigma error of the yaw rate (rad/s): gyro bias plus phone wobble in its mount. Heading
    // uncertainty from this grows with the time between fixes.
    private static final double GYRO_NOISE_RAD_S = 0.03;
    // If the newest gyro sample is older than this at a fix, the gyro is ignored for that step.
    private static final long GYRO_MAX_STALE_NS = 500_000_000L;
    private static final int YAW_BUF = 1024;   // ~20 s of history at ~50 Hz

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
    private Estimate lastSmoothed = null;   // posterior estimate at the last accepted fix

    // Cumulative yaw angle (rad, counter-clockwise about the vertical axis seen from above), as a
    // ring buffer of (time, cumulative angle). Differences between two times give the rotation.
    private final long[] yawT = new long[YAW_BUF];
    private final double[] yawCum = new double[YAW_BUF];
    private int yawCount = 0;               // samples ever added; newest is at (yawCount-1) % YAW_BUF
    private double stateYaw = Double.NaN;   // cumulative yaw at stateTimeNanos (NaN = no gyro)
    private long smoothedCount = 0;         // increments on every accepted fix

    /**
     * @param accelSigmaMps2 expected random acceleration (m/s^2). ~1.0 for walking, ~1.5-2.5 for
     *                       a car. Higher = trusts new fixes more (smooths less).
     */
    public TrackSmoothingFilter(double accelSigmaMps2) {
        this.accelSigma = accelSigmaMps2;
    }

    public void reset() {
        initialised = false;
        consecutiveRejects = 0;
        lastSmoothed = null;
        smoothedCount = 0;
        yawCount = 0;
        stateYaw = Double.NaN;
    }

    public boolean isInitialised() {
        return initialised;
    }

    // ================================================================ public API

    /**
     * Feeds one gyroscope sample.
     *
     * @param tNanos      monotonic time of the sample (SensorEvent.timestamp)
     * @param omegaUpRadS rotation rate about the vertical axis in rad/s, POSITIVE = counter-clockwise
     *                    seen from above (a left turn). Heading therefore DEcreases when positive.
     */
    public void addGyroYawRate(long tNanos, double omegaUpRadS) {
        if (yawCount == 0) {
            yawT[0] = tNanos;
            yawCum[0] = 0.0;
            yawCount = 1;
            return;
        }
        int head = (yawCount - 1) % YAW_BUF;
        long dtNanos = tNanos - yawT[head];
        if (dtNanos <= 0) {
            return;
        }
        // After a long silence don't integrate across it: we don't know what happened.
        double cum = dtNanos > 1_000_000_000L ? yawCum[head] : yawCum[head] + omegaUpRadS * dtNanos / 1e9;
        int next = yawCount % YAW_BUF;
        yawT[next] = tNanos;
        yawCum[next] = cum;
        yawCount++;
    }

    /** Cumulative yaw (rad) at a monotonic time, or NaN if the gyro has no data there. For logging. */
    public double cumulativeYawAt(long tNanos) {
        return yawAt(tNanos);
    }

    private double yawAt(long t) {
        if (yawCount == 0) {
            return Double.NaN;
        }
        int n = Math.min(yawCount, YAW_BUF);
        int head = (yawCount - 1) % YAW_BUF;
        if (t > yawT[head] + GYRO_MAX_STALE_NS) {
            return Double.NaN;            // gyro has gone quiet
        }
        if (t >= yawT[head]) {
            return yawCum[head];
        }
        for (int k = 1; k < n; k++) {
            int i = ((head - k) % YAW_BUF + YAW_BUF) % YAW_BUF;
            if (yawT[i] <= t) {
                int j = (i + 1) % YAW_BUF;
                double frac = (double) (t - yawT[i]) / (double) (yawT[j] - yawT[i]);
                return yawCum[i] + frac * (yawCum[j] - yawCum[i]);
            }
        }
        return Double.NaN;                // older than the buffer
    }

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
            double yawNow = yawAt(timeNanos);
            double phi = 0.0;
            double gyroNoise = 0.0;
            if (!Double.isNaN(stateYaw) && !Double.isNaN(yawNow)) {
                phi = yawNow - stateYaw;
                gyroNoise = GYRO_NOISE_RAD_S;
            }
            predict(x, P, (timeNanos - stateTimeNanos) / 1e9, accelSigma, phi, gyroNoise);
            stateTimeNanos = timeNanos;
            stateYaw = yawNow;
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
        publishSmoothed();
        return true;
    }

    /**
     * The smoothed estimate as of the most recent accepted fix, or null if none yet. This is the
     * filter's posterior (prediction blended with the fix), NOT an extrapolation to "now".
     */
    public Estimate latestSmoothed() {
        return lastSmoothed;
    }

    /** Increments each time a fix is accepted. Compare with the last value you recorded. */
    public long smoothedCount() {
        return smoothedCount;
    }

    private void publishSmoothed() {
        lastSmoothed = buildEstimate(x, P, lastPositionUpdateNanos);
        smoothedCount++;
    }

    private Estimate buildEstimate(double[] xs, double[][] Ps, long fixTimeNanos) {
        double lat = lat0 + xs[1] / METERS_PER_DEG_LAT;
        double lon = lon0 + xs[0] / metersPerDegLon;
        double speed = Math.hypot(xs[2], xs[3]);
        double bearing = speed >= MOVING_SPEED_MPS
                ? normaliseDeg(Math.toDegrees(Math.atan2(xs[2], xs[3])))
                : lastBearingDeg;

        // 68% radius of an isotropic 2-D Gaussian is ~1.5 sigma per axis.
        double accuracy = 1.5 * Math.sqrt((Ps[0][0] + Ps[1][1]) / 2.0);
        double speedAcc = Math.sqrt((Ps[2][2] + Ps[3][3]) / 2.0);

        return new Estimate(lat, lon, speed, bearing, accuracy, speedAcc, fixTimeNanos);
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
        stateYaw = yawAt(timeNanos);
        lastPositionUpdateNanos = timeNanos;
        consecutiveRejects = 0;
        initialised = true;
        publishSmoothed();
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

    /**
     * x <- F x ;  P <- F P F^T + Q  (constant-velocity model, random-acceleration noise).
     *
     * phi = rotation (rad, counter-clockwise) of the velocity vector over this step, from the
     * gyro. With phi = 0 this is the plain constant-velocity model. gyroNoise (rad/s) adds
     * uncertainty to the velocity DIRECTION in proportion to speed; 0 disables it.
     */
    private static void predict(double[] x, double[][] P, double dt, double sigmaA,
                                double phi, double gyroNoise) {
        double c = Math.cos(phi);
        double s = Math.sin(phi);
        double ch = Math.cos(phi / 2.0);   // position moves along the half-turned velocity
        double sh = Math.sin(phi / 2.0);

        double ve = x[2];
        double vn = x[3];
        x[0] += dt * (ch * ve - sh * vn);
        x[1] += dt * (sh * ve + ch * vn);
        x[2] = c * ve - s * vn;
        x[3] = s * ve + c * vn;

        double[][] F = {
                {1, 0, dt * ch, -dt * sh},
                {0, 1, dt * sh, dt * ch},
                {0, 0, c, -s},
                {0, 0, s, c}
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

        if (gyroNoise > 0.0) {
            double speed = Math.hypot(x[2], x[3]);
            if (speed > 0.1) {
                double q = Math.pow(speed * gyroNoise * dt, 2);   // (speed * heading error)^2
                double nE = -x[3] / speed;                        // unit vector perpendicular to velocity
                double nN = x[2] / speed;
                FPFt[2][2] += q * nE * nE;
                FPFt[3][3] += q * nN * nN;
                FPFt[2][3] += q * nE * nN;
                FPFt[3][2] += q * nE * nN;
            }
        }

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
