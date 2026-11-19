package uk.ac.cam.cares.jps.data;

import android.os.Handler;
import android.os.Looper;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import uk.ac.cam.cares.jps.utils.RepositoryCallback;

/**
 * Waits for a background agent job to leave the "queued"/"running" state.
 * <p>
 * The trip-agent and exposure-calculation-agent both return HTTP 202 as soon as a job is queued,
 * so a 202 only means "accepted", not "done". Their /jobs/active endpoint lists only queued and
 * running jobs, so a job that is no longer listed has finished (it cannot be distinguished from a
 * failed job through that endpoint).
 */
public final class JobPoller {

    private static final Logger LOGGER = Logger.getLogger(JobPoller.class);
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long TIMEOUT_MS = 10 * 60 * 1000;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    /** Returns the raw JSON array text of the user's active jobs. */
    public interface ActiveJobsFetcher {
        void fetch(RepositoryCallback<String> callback);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String jobId;
    private final ActiveJobsFetcher fetcher;
    private final RepositoryCallback<Void> callback;
    private final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    private int consecutiveErrors = 0;

    private JobPoller(String jobId, ActiveJobsFetcher fetcher, RepositoryCallback<Void> callback) {
        this.jobId = jobId;
        this.fetcher = fetcher;
        this.callback = callback;
    }

    /**
     * @param queuedResponse body of the 202 response, e.g. {"job_id": "...", "status": "queued"}
     * @param fetcher        fetches the active jobs of the matching agent
     * @param callback       onSuccess once the job is no longer active, onFailure on timeout or repeated errors
     */
    public static void awaitCompletion(String queuedResponse, ActiveJobsFetcher fetcher, RepositoryCallback<Void> callback) {
        String jobId;
        try {
            jobId = new JSONObject(queuedResponse).getString("job_id");
        } catch (JSONException e) {
            callback.onFailure(new Exception("Agent response did not contain a job_id", e));
            return;
        }
        new JobPoller(jobId, fetcher, callback).poll();
    }

    private void poll() {
        fetcher.fetch(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String body) {
                consecutiveErrors = 0;
                boolean stillActive;
                try {
                    stillActive = isActive(body);
                } catch (JSONException e) {
                    callback.onFailure(new Exception("Could not parse active jobs response", e));
                    return;
                }
                if (!stillActive) {
                    LOGGER.info("Job " + jobId + " is no longer active");
                    callback.onSuccess(null);
                } else {
                    scheduleNext();
                }
            }

            @Override
            public void onFailure(Throwable error) {
                consecutiveErrors++;
                LOGGER.warn("Polling job " + jobId + " failed (" + consecutiveErrors + ")", error);
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    callback.onFailure(new Exception("Could not check status of job " + jobId, error));
                } else {
                    scheduleNext();
                }
            }
        });
    }

    private boolean isActive(String body) throws JSONException {
        JSONArray jobs = new JSONArray(body);
        for (int i = 0; i < jobs.length(); i++) {
            if (jobId.equals(jobs.getJSONObject(i).optString("id"))) {
                return true;
            }
        }
        return false;
    }

    private void scheduleNext() {
        if (System.currentTimeMillis() > deadline) {
            callback.onFailure(new Exception("Timed out waiting for job " + jobId));
            return;
        }
        handler.postDelayed(this::poll, POLL_INTERVAL_MS);
    }
}
