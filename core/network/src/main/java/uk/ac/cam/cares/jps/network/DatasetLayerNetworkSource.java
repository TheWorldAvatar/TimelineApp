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
 * Network source for fetching an arbitrary vector layer (points, lines or polygons)
 * from GeoServer via GeoServerJwtProxy, keyed by WFS typeName — same endpoint
 * previously used only for the hawker centre layer, no viewparams needed since
 * these reference layers aren't date-scoped.
 */
public class DatasetLayerNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(DatasetLayerNetworkSource.class);

    private final RequestQueue requestQueue;
    private final Context context;

    public DatasetLayerNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * @param accessToken Bearer token obtained from LoginRepository
     * @param typeName    fully-qualified WFS typeName, e.g. "twa:hawker_centres"
     */
    public void getDatasetLayer(String accessToken, String typeName, Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        String uri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.geoserver_jwt_proxy_geoserver_twa_wfs))
                .addQueryParameter("service", "WFS")
                .addQueryParameter("version", "1.0.0")
                .addQueryParameter("request", "GetFeature")
                .addQueryParameter("typeName", typeName)
                .addQueryParameter("outputFormat", "application/json")
                .build().toString();

        LOGGER.info("Fetching dataset layer '" + typeName + "' via jwt proxy: " + uri);

        StringRequest request = buildRequest(accessToken, uri, onSuccess, onFailure);
        request.setRetryPolicy(new DefaultRetryPolicy(15000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    @NonNull
    private StringRequest buildRequest(String accessToken, String uri, Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        return new StringRequest(Request.Method.GET, uri, onSuccess, error -> {
            LOGGER.error("Failed to fetch dataset layer", error);
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