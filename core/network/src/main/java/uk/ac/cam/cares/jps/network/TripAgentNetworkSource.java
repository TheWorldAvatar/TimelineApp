package uk.ac.cam.cares.jps.network;

import android.content.Context;

import com.android.volley.AuthFailureError;
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

/**
 * Network source for triggering trip detection on the trip-agent for the authenticated user.
 */
public class TripAgentNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(TripAgentNetworkSource.class);
    private final RequestQueue requestQueue;
    private final Context context;

    public TripAgentNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * Trigger trip/visit detection for the authenticated user's trajectories.
     *
     * @param accessToken Keycloak bearer token for the current user
     * @param onSuccess   Success callback
     * @param onFailure   Failure callback
     */
    public void processTrajectoryForTimeline(String accessToken, Long lowerbound, Long upperbound,
                                            Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        HttpUrl.Builder urlBuilder = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.tripagent_process_trajectory_for_timeline));

        if (lowerbound != null) {
            urlBuilder.addQueryParameter("lowerbound", Instant.ofEpochMilli(lowerbound).toString());
        }
        if (upperbound != null) {
            urlBuilder.addQueryParameter("upperbound", Instant.ofEpochMilli(upperbound).toString());
        }

        String uri = urlBuilder.build().toString();
        LOGGER.info("Triggering trip-agent: " + uri);

        StringRequest request = new StringRequest(Request.Method.POST, uri, onSuccess, onFailure) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(10000, 2, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }
}