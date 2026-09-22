package uk.ac.cam.cares.jps.network;

import android.content.Context;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import uk.ac.cam.cares.jps.model.ExposureDataset;

public class BlazegraphNetworkSource {
    private static final Logger LOGGER = Logger.getLogger(BlazegraphNetworkSource.class);
    private final RequestQueue requestQueue;
    private final Context context;

    public BlazegraphNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    public void getDatasets(Response.Listener<List<ExposureDataset>> onSuccess,
                            Response.ErrorListener onFailure) {

        String sparql =
                "PREFIX dcterms: <http://purl.org/dc/terms/>\n" +
                "PREFIX dcat: <http://www.w3.org/ns/dcat#>\n" +
                "SELECT DISTINCT ?table_name WHERE {\n" +
                "  ?dataset a dcat:Dataset ;\n" +
                "           dcterms:title ?table_name .\n" +
                "} ORDER BY ?table_name";

        String url = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port))
                .newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.blazegraph_sparql_path))
                .build().toString();

        StringRequest request = new StringRequest(Request.Method.POST, url, s -> {
            try {
                JSONArray bindings = new JSONObject(s)
                        .getJSONObject("results")
                        .getJSONArray("bindings");

                List<ExposureDataset> datasets = new ArrayList<>();
                for (int i = 0; i < bindings.length(); i++) {
                    String tableName = bindings.getJSONObject(i)
                            .getJSONObject("table_name")
                            .getString("value");
                    datasets.add(new ExposureDataset(tableName, tableName));
                }
                onSuccess.onResponse(datasets);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }, onFailure) {

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Accept", "application/sparql-results+json");
                return headers;
            }

            @Override
            public byte[] getBody() throws AuthFailureError {
                try {
                    return ("query=" + URLEncoder.encode(sparql, "UTF-8")).getBytes("UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String getBodyContentType() {
                return "application/x-www-form-urlencoded; charset=UTF-8";
            }
        };

        requestQueue.add(request);
    }
}