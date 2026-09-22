package uk.ac.cam.cares.jps.network;

import android.content.Context;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;

import java.time.Instant;

import okhttp3.HttpUrl;

/**
 * Network source for triggering exposure calculation on exposure-calculation-agent.
 * No auth header required — this agent authenticates via the stack's internal
 * federation endpoint, not per-request bearer tokens (see exposure-calculation-agent README).
 */
public class ExposureCalculationAgentNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(ExposureCalculationAgentNetworkSource.class);
    private final RequestQueue requestQueue;
    private final Context context;

    public ExposureCalculationAgentNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * Trigger an exposure calculation for the given subject.
     *
     * @param subjectIri  IRI of the point time series, same subject passed to trip-agent
     * @param rdfType     IRI of the calculation type, e.g. .../ontoexposure/TrajectoryCount
     * @param distance    buffer distance in metres
     * @param exposureTable table name of exposure dataset, uploaded via stack data uploader
     */
    public void triggerCalculation(String subjectIri, String rdfType, String distance, String exposureTable,
                                    Long lowerbound, Long upperbound,
                                    Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        HttpUrl.Builder urlBuilder = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.exposurecalculationagent_triggerCalculation))
                .addQueryParameter("rdf_type", rdfType)
                .addQueryParameter("distance", distance)
                .addQueryParameter("subject", subjectIri)
                .addQueryParameter("exposure_table", exposureTable);

        if (lowerbound != null) {
            urlBuilder.addQueryParameter("lowerbound", Instant.ofEpochMilli(lowerbound).toString());
        }
        if (upperbound != null) {
            urlBuilder.addQueryParameter("upperbound", Instant.ofEpochMilli(upperbound).toString());
        }

        String uri = urlBuilder.build().toString();
        LOGGER.info("Triggering exposure-calculation-agent: " + uri);

        StringRequest request = new StringRequest(Request.Method.POST, uri, onSuccess, error -> {
            LOGGER.error("exposure-calculation-agent call failed", error);
            onFailure.onErrorResponse(error);
        });
        request.setRetryPolicy(new DefaultRetryPolicy(10000, 2, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }
}