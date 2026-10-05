package uk.ac.cam.cares.jps.network;

import android.content.Context;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
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

        String url = HttpUrl.get(
                context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)
        )
        .newBuilder()
        .addPathSegments(
                context.getString(uk.ac.cam.cares.jps.utils.R.string.blazegraph_sparql_path)
        )
        .build()
        .toString();

        StringRequest request = new StringRequest(
                Request.Method.GET,
                url,
                response -> {
                    try {
                        JSONArray jsonArray = new JSONArray(response);

                        List<ExposureDataset> datasets = new ArrayList<>();

                        for (int i = 0; i < jsonArray.length(); i++) {
                            JSONObject dataset = jsonArray.getJSONObject(i);

                            String label = dataset.getString("label");
                            String tableName = dataset.getString("table_name");

                            datasets.add(new ExposureDataset(label, tableName));;
                        }

                        onSuccess.onResponse(datasets);

                    } catch (JSONException e) {
                        LOGGER.error("Failed to parse dataset response", e);
                        onFailure.onErrorResponse(
                                new VolleyError("Failed to parse dataset response", e)
                        );
                    }
                },
                onFailure
        );

        requestQueue.add(request);
    }

}