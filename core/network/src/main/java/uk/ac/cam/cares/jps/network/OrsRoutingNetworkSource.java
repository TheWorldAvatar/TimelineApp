package uk.ac.cam.cares.jps.network;

import android.content.Context;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import okhttp3.HttpUrl;

// NEW FILE
// does NOT take a Keycloak access token:
// ORS is an external service with its own API key.
public class OrsRoutingNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(OrsRoutingNetworkSource.class);
    private static final String AUTOCOMPLETE_TAG = "ors_autocomplete"; // CHANGE: NEW, used to cancel stale searches
    private final RequestQueue requestQueue;
    private final Context context;

    public OrsRoutingNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * @param profile          ORS profile, e.g. driving-car, cycling-regular, foot-walking
     * @param alternativeCount number of routes wanted; null or <= 1 asks for a single route
     */
    public void getRoutes(String profile,
                          double startLon, double startLat,
                          double endLon, double endLat,
                          Integer alternativeCount,
                          Response.Listener<JSONObject> onSuccess, Response.ErrorListener onFailure) {

        // Fail early with a readable message if the key was never filled in.
        String apiKey = context.getString(uk.ac.cam.cares.jps.utils.R.string.ors_api_key);
        if (apiKey.isEmpty()) {
            onFailure.onErrorResponse(new VolleyError("ORS API key is not set (ors_api_key in developer_config.xml)"));
            return;
        }

        // Builds e.g. https://api.openrouteservice.org/v2/directions/driving-car/geojson
        // Using the /geojson variant means the response is a FeatureCollection, so there is
        // no polyline decoding and the coordinates can go straight into a Mapbox GeoJSON source.
        String uri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.ors_base_url)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.ors_directions_path))
                .addPathSegment(profile)
                .addPathSegment("geojson")
                .build().toString();

        JSONObject body = new JSONObject();
        try {
            // ORS expects [longitude, latitude], the reverse of "lat, lng" habits.
            JSONArray coordinates = new JSONArray()
                    .put(new JSONArray().put(startLon).put(startLat))
                    .put(new JSONArray().put(endLon).put(endLat));
            body.put("coordinates", coordinates);

            // Only ask for alternatives when more than one route is wanted.
            // target_count = how many routes; share_factor = max overlap with the main route
            // (lower means more distinct); weight_factor = how much longer an alternative may be.
            if (alternativeCount != null && alternativeCount > 1) {
                body.put("alternative_routes", new JSONObject()
                        .put("target_count", alternativeCount)
                        .put("share_factor", 0.6)
                        .put("weight_factor", 1.4));
            }
        } catch (JSONException e) {
            onFailure.onErrorResponse(new VolleyError(e));
            return;
        }

        LOGGER.info("Requesting ORS routes: " + uri);

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, uri, body, onSuccess, error -> {
            LOGGER.error("ORS directions call failed", error);
            onFailure.onErrorResponse(error);
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", apiKey); // ORS takes the raw key, no "Bearer " prefix
                headers.put("Accept", "application/json, application/geo+json");
                return headers;
            }
        };
        // 30 s timeout and 1 retry (your agent calls use a huge timeout because they are slow;
        // a routing call should not be).
        request.setRetryPolicy(new DefaultRetryPolicy(30000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }
    
    public void searchPlaces(String text, Double focusLongitude, Double focusLatitude, int size,
                             Response.Listener<JSONObject> onSuccess, Response.ErrorListener onFailure) {
        String apiKey = context.getString(uk.ac.cam.cares.jps.utils.R.string.ors_api_key);
        if (apiKey.isEmpty()) {
            onFailure.onErrorResponse(new VolleyError("ORS API key is not set (ors_api_key in developer_config.xml)"));
            return;
        }

        HttpUrl.Builder builder = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.ors_base_url)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.ors_autocomplete_path))
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("text", text)
                .addQueryParameter("size", String.valueOf(size));

        // Focus point = bias results towards where the user is looking on the map.
        // (Add .addQueryParameter("boundary.country", "SG") here if you want to limit search to one country.)
        if (focusLongitude != null && focusLatitude != null) {
            builder.addQueryParameter("focus.point.lon", String.valueOf(focusLongitude));
            builder.addQueryParameter("focus.point.lat", String.valueOf(focusLatitude));
        }

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, builder.build().toString(), null,
                onSuccess, error -> {
            LOGGER.error("ORS autocomplete call failed", error);
            onFailure.onErrorResponse(error);
        });

        // While the user types, only the newest search matters: cancel any earlier one still waiting.
        // (A cancelled Volley request never calls its listeners.)
        request.setTag(AUTOCOMPLETE_TAG);
        requestQueue.cancelAll(AUTOCOMPLETE_TAG);
        request.setRetryPolicy(new DefaultRetryPolicy(10000, 0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }
}