package uk.ac.cam.cares.jps.network;

import android.content.Context;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import okhttp3.HttpUrl;

public class ExposureCalculationAgentNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(ExposureCalculationAgentNetworkSource.class);
    private final RequestQueue requestQueue;
    private final Context context;

    public ExposureCalculationAgentNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * @param accessToken bearer token for exposure-calculation-agent
     * @param rdfType     IRI of the calculation type, e.g. .../ontoexposure/TrajectoryCount
     * @param distance    buffer distance in metres
     * @param datasetIri  IRI of the exposure dataset (from ExposureFeatureInfoAgent's dataset list)
     */
    public void triggerCalculation(String accessToken, String rdfType, String distance, String datasetIri,
                                    Long lowerbound, Long upperbound,
                                    Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        HttpUrl.Builder urlBuilder = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.exposurecalculationagent_triggerCalculation))
                .addQueryParameter("rdf_type", rdfType)
                .addQueryParameter("distance", distance)
                .addQueryParameter("dataset_iri", datasetIri);

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
        }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };
        request.setRetryPolicy(new DefaultRetryPolicy(1000000, 2, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }
}