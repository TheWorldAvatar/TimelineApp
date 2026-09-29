package uk.ac.cam.cares.jps.network;

import android.content.Context;
import com.android.volley.*;
import com.android.volley.toolbox.StringRequest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import uk.ac.cam.cares.jps.model.ExposureDataset;
import org.apache.log4j.Logger;

public class ExposureFeatureInfoNetworkSource {
    private static final Logger LOGGER = Logger.getLogger(ExposureFeatureInfoNetworkSource.class);
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

        LOGGER.info("Requesting exposure timeline results: " + uri);
        
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

        /**
     * @param rdfType optional dcat:Dataset subtype IRI to filter by; pass null for the default
     *                (http://www.w3.org/ns/dcat#Dataset), matching ExposureFeatureInfoAgent's default.
     */
    public void getDatasets(String accessToken, String rdfType,
                             Response.Listener<List<ExposureDataset>> onSuccess,
                             Response.ErrorListener onFailure) {
        HttpUrl.Builder urlBuilder = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.exposurefeatureinfoagent_datasets));
        if (rdfType != null) {
            urlBuilder.addQueryParameter("rdf_type", rdfType);
        }
        String uri = urlBuilder.build().toString();

        StringRequest request = new StringRequest(Request.Method.GET, uri, s -> {
            try {
                JSONArray jsonArray = new JSONArray(s);
                List<ExposureDataset> datasets = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject dataset = jsonArray.getJSONObject(i);
                    String name = dataset.getString("name");
                    String iri = dataset.getString("iri");
                    datasets.add(new ExposureDataset(name, iri));
                }
                onSuccess.onResponse(datasets);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }, onFailure) {
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