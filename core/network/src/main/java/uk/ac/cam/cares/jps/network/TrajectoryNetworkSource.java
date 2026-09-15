package uk.ac.cam.cares.jps.network;

import android.content.Context;

import androidx.annotation.NonNull;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import okhttp3.HttpUrl;

/**
 * Network source for constructing, sending and processing trajectory related requests to server
 */
public class TrajectoryNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(TrajectoryNetworkSource.class);
    private final RequestQueue requestQueue;
    private final Context context;

    /**
     * Constructor of the class. The instantiation is handled by dependency injection.
     * @param requestQueue Volley queue for network request.
     * @param context App context
     */
    public TrajectoryNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * Get trajectory from server. It consists two steps:
     * 1. create geoserver layers and Postgres SQL functions with TrajectoryQueryAgent
     * 2. get geojson from geoserver for visualisation with iris
     *
     * @param onSuccessUpper Success callback
     * @param onFailureUpper Failure callback
     * @param lowerbound Unix timestamp for the lower bound of the date range
     * @param upperbound Unix timestamp for the upper bound of the date range
     */
    public void getTrajectory(String accessToken, long lowerbound, long upperbound, Response.Listener<String> onSuccessUpper, Response.ErrorListener onFailureUpper) {
        // --- ADD: short id shared across createLayer + primary + fallback log lines for this one call ---
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        LOGGER.info("[TIMING][" + requestId + "] getTrajectory() called for range [" + lowerbound + ", " + upperbound + "]");

        String createLayerUri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.trajectoryqueryagent_createLayer))
                .build().toString();
        LOGGER.info(createLayerUri);

        StringRequest createLayerRequest = buildCreateLayerRequest(accessToken, onSuccessUpper, onFailureUpper, createLayerUri, lowerbound, upperbound, requestId);
        requestQueue.add(createLayerRequest);
    }

    @NonNull
    private StringRequest buildCreateLayerRequest(String accessToken, Response.Listener<String> onSuccessUpper, Response.ErrorListener onFailureUpper, String createLayerUri, long lowerbound, long upperbound, String requestId) {
        // --- ADD: capture the moment we're about to send the createLayer request ---
        final long createLayerStart = System.currentTimeMillis();
        LOGGER.info("[TIMING][" + requestId + "] Sending createLayer request at " + createLayerStart);

        Response.Listener<String> onCreateLayerSuccess = s -> {
            // --- ADD: log elapsed time as soon as the response comes back ---
            long createLayerElapsed = System.currentTimeMillis() - createLayerStart;
            LOGGER.info("[TIMING][" + requestId + "] createLayer took " + createLayerElapsed + "ms");

            try {
                // Log the full server response
                LOGGER.debug("Full server response: " + s);
                JSONObject rawResponse = new JSONObject(s);

                StringRequest getTrajectoryRequest = buildGetTrajectoryRequest(accessToken, onSuccessUpper, onFailureUpper, rawResponse, lowerbound, upperbound, requestId);
                if (getTrajectoryRequest != null) {
                    requestQueue.add(getTrajectoryRequest);
                }
            } catch (JSONException e) {
                LOGGER.error("Received XML response instead of JSON: " + s);
                onFailureUpper.onErrorResponse(new VolleyError("geoserver error"));
            }
        };

        // --- ADD: wrap the original error listener to log elapsed time on failure too ---
        Response.ErrorListener onCreateLayerError = error -> {
            long createLayerElapsed = System.currentTimeMillis() - createLayerStart;
            LOGGER.error("[TIMING][" + requestId + "] createLayer FAILED after " + createLayerElapsed + "ms");
            onFailureUpper.onErrorResponse(error);
        };

        return new StringRequest(Request.Method.GET, createLayerUri, onCreateLayerSuccess, onCreateLayerError) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };
    }

    private StringRequest buildGetTrajectoryRequest(String accessToken, Response.Listener<String> onSuccessUpper, Response.ErrorListener onFailureUpper, JSONObject rawResponse, long lowerbound, long upperbound, String requestId) throws JSONException {
        if (!rawResponse.has("message")) {
            LOGGER.error("Not able to handle the agent response. Please check the backend");
            onFailureUpper.onErrorResponse(new VolleyError("Server error"));
            return null;
        }

        if (rawResponse.getString("message").equals(context.getString(uk.ac.cam.cares.jps.utils.R.string.trajectoryagent_no_phone_id_on_the_user))) {
            onFailureUpper.onErrorResponse(new VolleyError(context.getString(uk.ac.cam.cares.jps.utils.R.string.trajectoryagent_no_phone_id_on_the_user)));
            return null;
        } else if (rawResponse.getString("message").equals(context.getString(uk.ac.cam.cares.jps.utils.R.string.trajectoryagent_measurement_iri_missing))) {
            LOGGER.info("No trajectory retrieved for this user id");
            onSuccessUpper.onResponse("");
            return null;
        } else if (!rawResponse.getString("message").equals(context.getString(uk.ac.cam.cares.jps.utils.R.string.trajectoryagent_layer_created))) {
            LOGGER.error("Not able to handle the agent response. Please check the backend");
            onFailureUpper.onErrorResponse(new VolleyError("Server error"));
            return null;
        }

        // Primary request URL
        /*
        String getTrajectoryActivityUri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.geoserver_jwt_proxy_geoserver_twa_wfs))
                .addQueryParameter("service", "WFS")
                .addQueryParameter("version", "1.0.0")
                .addQueryParameter("request", "GetFeature")
                .addQueryParameter("typeName", "twa:trajectoryUserIdByActivity")
                .addQueryParameter("outputFormat", "application/json")
                .addQueryParameter("viewparams", String.format(Locale.ENGLISH, "upperbound:%d;lowerbound:%d;", upperbound, lowerbound))
                .build().toString();
         */

        // Fallback request URL
        String getTrajectoryDefaultUri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.geoserver_jwt_proxy_geoserver_twa_wfs))
                .addQueryParameter("service", "WFS")
                .addQueryParameter("version", "1.0.0")
                .addQueryParameter("request", "GetFeature")
                .addQueryParameter("typeName", "twa:trajectoryUserId")
                .addQueryParameter("outputFormat", "application/json")
                .addQueryParameter("viewparams", String.format(Locale.ENGLISH, "upperbound:%d;lowerbound:%d;", upperbound, lowerbound))
                .build().toString();

        LOGGER.info("Print out URI: " + getTrajectoryDefaultUri);

        // --- ADD: capture the moment we're about to send the primary WFS request ---
        final long primaryStart = System.currentTimeMillis();
        LOGGER.info("[TIMING][" + requestId + "] Sending trajectoryUserId request at " + primaryStart);

        Response.Listener<String> onGetTrajectorySuccess = s1 -> {
            // --- ADD: log elapsed time for the primary request as soon as it comes back ---
            long primaryElapsed = System.currentTimeMillis() - primaryStart;
            LOGGER.info("[TIMING][" + requestId + "] trajectoryUserId took " + primaryElapsed + "ms");

            try {
                LOGGER.debug("Full server response: " + s1);

                JSONObject trajectoryResponse = new JSONObject(s1);

                int totalFeatures = trajectoryResponse.getInt("totalFeatures");

                LOGGER.info(
                        "[TIMING][" + requestId + "] trajectoryUserId returned "
                                + totalFeatures + " features"
                );

                if (totalFeatures == 0) {
                    LOGGER.info("No trajectory found for trajectoryUserId");
                    onSuccessUpper.onResponse("");
                    return;
                }

                onSuccessUpper.onResponse(trajectoryResponse.toString());

/*
                try {
                LOGGER.debug("Full server response: " + s1);

                JSONObject trajectoryResponse = new JSONObject(s1);
                if (trajectoryResponse.getInt("totalFeatures") == 0 ||
                        (trajectoryResponse.getInt("totalFeatures") == 1 && trajectoryResponse
                                .getJSONArray("features")
                                .getJSONObject(0)
                                .getString("geometry").equals("null"))) {
                    LOGGER.info("No trajectory from getTrajectoryActivityUri, trying getTrajectoryDefaultUri...");

                    // --- ADD: separate timer for the fallback request ---
                    final long fallbackStart = System.currentTimeMillis();
                    LOGGER.info("[TIMING][" + requestId + "] Sending trajectoryUserId fallback request at " + fallbackStart);

                    // Fallback request
                    StringRequest fallbackRequest = new StringRequest(Request.Method.GET, getTrajectoryDefaultUri,
                            result -> {
                                // --- ADD: log elapsed time for the fallback request ---
                                long fallbackElapsed = System.currentTimeMillis() - fallbackStart;
                                LOGGER.info("[TIMING][" + requestId + "] trajectoryUserId fallback took " + fallbackElapsed + "ms");
                                onSuccessUpper.onResponse(result);
                            },
                            error -> {
                                // --- ADD: log elapsed time on fallback failure too ---
                                long fallbackElapsed = System.currentTimeMillis() - fallbackStart;
                                LOGGER.error("[TIMING][" + requestId + "] trajectoryUserId fallback FAILED after " + fallbackElapsed + "ms");
                                onFailureUpper.onErrorResponse(error);
                            }) {
                        @Override
                        public Map<String, String> getHeaders() throws AuthFailureError {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Authorization", "Bearer " + accessToken);
                            return headers;
                        }
                    };

                    fallbackRequest.setRetryPolicy(new DefaultRetryPolicy(10000, 2, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
                    requestQueue.add(fallbackRequest);
                } else {
                    onSuccessUpper.onResponse(trajectoryResponse.toString());
                }
 */
            } catch (JSONException e) {
                LOGGER.error("Received XML response instead of JSON: " + s1);
                LOGGER.error("Received invalid JSON from GeoServer", e);
                onFailureUpper.onErrorResponse(new VolleyError("Geoserver error"));
            }
        };

        // --- ADD: wrap the original error listener to log elapsed time on primary failure ---
        Response.ErrorListener onGetTrajectoryError = error -> {
            long primaryElapsed = System.currentTimeMillis() - primaryStart;
            LOGGER.error("[TIMING][" + requestId + "] trajectoryUserIdByActivity FAILED after " + primaryElapsed + "ms");
            onFailureUpper.onErrorResponse(error);
        };

        StringRequest request = new StringRequest(Request.Method.GET, getTrajectoryDefaultUri, onGetTrajectorySuccess, onGetTrajectoryError) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(1000000, 2, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        return request;
    }
}