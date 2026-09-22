package uk.ac.cam.cares.jps.network;

import android.content.Context;
import com.android.volley.*;
import com.android.volley.toolbox.StringRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import okhttp3.HttpUrl;

public class ExposureFeatureInfoNetworkSource {
    private final RequestQueue requestQueue;
    private final Context context;

    public ExposureFeatureInfoNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    public void getTimelineResults(String accessToken, long lowerbound, long upperbound,
                                    Response.Listener<String> onSuccess, Response.ErrorListener onFailure) {
        String uri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.exposurefeatureinfoagent_timeline))
                .addQueryParameter("lowerbound", Instant.ofEpochMilli(lowerbound).toString())
                .addQueryParameter("upperbound", Instant.ofEpochMilli(upperbound).toString())
                .build().toString();

        StringRequest request = new StringRequest(Request.Method.GET, uri, onSuccess, onFailure) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };
        request.setRetryPolicy(new DefaultRetryPolicy(10000, 2, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }
}