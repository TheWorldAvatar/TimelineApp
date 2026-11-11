package uk.ac.cam.cares.jps.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.model.YearMonthCompositeKey;
import uk.ac.cam.cares.jps.network.DatesWithTrajectoryNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * A repository that manages dates information for trajectory.
 *
 * CHANGED behaviour (this class is a Hilt @Singleton, so the state below outlives the fragment):
 *  - the server is asked at most once per calendar day; later calls the same day get the cached result
 *  - a failed request is retried automatically with exponential backoff (2 s, 4 s, 8 s)
 *  - if every retry fails, the error is logged and passed to the callers; nothing is swallowed.
 *    The next call (e.g. the next time the Timeline screen is created) starts a fresh attempt
 *    because nothing was cached.
 *  - concurrent callers share a single in-flight request instead of firing duplicates
 */
public class DatesWithTrajectoryRepository {
    private static final int MAX_RETRIES = 3;               // attempts = 1 + MAX_RETRIES
    private static final long BASE_BACKOFF_MS = 2000;       // doubles each retry

    private final LoginRepository loginRepository;
    private final Context context;
    private final DatesWithTrajectoryNetworkSource datesWithTrajectoryNetworkSource;
    private final Logger LOGGER = LogManager.getLogger(DatesWithTrajectoryRepository.class);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // all fields below are guarded by `this`
    private Map<YearMonthCompositeKey, List<Integer>> cachedDates;
    private LocalDate cachedOn;                              // the day the cache was fetched
    private boolean requestInFlight = false;
    private final List<RepositoryCallback<Map<YearMonthCompositeKey, List<Integer>>>> waiting = new ArrayList<>();

    /**
     * Constructor of the class. Instantiation of this class is done by dependency injection.
     * @param datesWithTrajectoryNetworkSource Network source managed by this class, which is responsible for date related request.
     * @param loginRepository Repository responsible for user login related functions.
     * @param context Context of the app.
     */
    public DatesWithTrajectoryRepository(DatesWithTrajectoryNetworkSource datesWithTrajectoryNetworkSource,
                                         LoginRepository loginRepository,
                                         Context context) {
        this.datesWithTrajectoryNetworkSource = datesWithTrajectoryNetworkSource;
        this.loginRepository = loginRepository;
        this.context = context;
    }

    /**
     * Get dates that have trajectory data for the logged in user.
     * Served from the cache if it was fetched today, otherwise requested from the server with retries.
     *
     * @param timezone The phone's current timezone
     * @param callback Callback to notify UI level components when responses are returned from server
     */
    public void getDatesWithTrajectory(String timezone, RepositoryCallback<Map<YearMonthCompositeKey, List<Integer>>> callback) {
        synchronized (this) {
            if (cachedDates != null && LocalDate.now().equals(cachedOn)) {
                Map<YearMonthCompositeKey, List<Integer>> cached = cachedDates;
                mainHandler.post(() -> callback.onSuccess(cached));
                return;
            }
            waiting.add(callback);
            if (requestInFlight) return;      // someone is already fetching; we will be notified
            requestInFlight = true;
        }
        attempt(timezone, 0);
    }

    private void attempt(String timezone, int attemptNumber) {
        loginRepository.getAccessToken(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String accessToken) {
                datesWithTrajectoryNetworkSource.getDates(accessToken, timezone,
                        dates -> finishSuccess(dates),
                        volleyError -> handleFailure(timezone, attemptNumber,
                                new Throwable("Failed to get dates with data", volleyError)));
            }

            @Override
            public void onFailure(Throwable error) {
                handleFailure(timezone, attemptNumber, error);
            }
        });
    }

    private void handleFailure(String timezone, int attemptNumber, Throwable error) {
        if (attemptNumber < MAX_RETRIES) {
            long delay = BASE_BACKOFF_MS << attemptNumber;
            LOGGER.warn("getDatesWithData failed (attempt " + (attemptNumber + 1) + " of "
                    + (MAX_RETRIES + 1) + "), retrying in " + delay + " ms", error);
            mainHandler.postDelayed(() -> attempt(timezone, attemptNumber + 1), delay);
            return;
        }

        LOGGER.error("getDatesWithData failed after " + (MAX_RETRIES + 1) + " attempts", error);
        List<RepositoryCallback<Map<YearMonthCompositeKey, List<Integer>>>> toNotify;
        synchronized (this) {
            requestInFlight = false;
            toNotify = new ArrayList<>(waiting);
            waiting.clear();
        }
        for (RepositoryCallback<Map<YearMonthCompositeKey, List<Integer>>> cb : toNotify) {
            cb.onFailure(error);
        }
    }

    private void finishSuccess(Map<YearMonthCompositeKey, List<Integer>> dates) {
        List<RepositoryCallback<Map<YearMonthCompositeKey, List<Integer>>>> toNotify;
        synchronized (this) {
            cachedDates = dates;
            cachedOn = LocalDate.now();
            requestInFlight = false;
            toNotify = new ArrayList<>(waiting);
            waiting.clear();
        }
        for (RepositoryCallback<Map<YearMonthCompositeKey, List<Integer>>> cb : toNotify) {
            cb.onSuccess(dates);
        }
    }
}