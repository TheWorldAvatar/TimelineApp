package uk.ac.cam.cares.jps.network;

import android.content.Context;

import androidx.annotation.NonNull;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

import okhttp3.HttpUrl;

/**
 * Network source for fetching the static hawker centre point layer from GeoServer,
 * via GeoServerJwtProxy — same endpoint as trajectory layers, no viewparams needed
 * since this layer isn't date-scoped.
 */
public class HawkerCentreNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(HawkerCentreNetworkSource.class);

    private final RequestQueue requestQueue;
    private final Context context;

    public HawkerCentreNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * @param accessToken Bearer token obtained from LoginRepository
     */
    public void getHawkerCentres(String accessToken, Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        String uri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.geoserver_jwt_proxy_geoserver_twa_wfs))
                .addQueryParameter("service", "WFS")
                .addQueryParameter("version", "1.0.0")
                .addQueryParameter("request", "GetFeature")
                .addQueryParameter("typeName", "twa:hawker_centre")
                .addQueryParameter("outputFormat", "application/json")
                .build().toString();

        LOGGER.info("Fetching hawker centres via jwt proxy: " + uri);

        StringRequest request = buildRequest(accessToken, uri, onSuccess, onFailure);
        request.setRetryPolicy(new DefaultRetryPolicy(15000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    @NonNull
    private StringRequest buildRequest(String accessToken, String uri, Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        return new StringRequest(Request.Method.GET, uri, onSuccess, error -> {
            LOGGER.error("Failed to fetch hawker centres", error);
            onFailure.onErrorResponse(error);
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };
    }
}