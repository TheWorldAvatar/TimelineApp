package uk.ac.cam.cares.jps.sensor.source.network;

import android.content.Context;
import android.util.Base64;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.sensor.source.database.SensorLocalSource;
import uk.ac.cam.cares.jps.sensor.source.database.model.entity.UnsentData;

import static uk.ac.cam.cares.jps.utils.Utils.computeHash;

/**
 * A class that commits data to the network database.
 */
public class SensorNetworkSource {
    Context context;
    RequestQueue requestQueue;
    Logger LOGGER = Logger.getLogger(SensorNetworkSource.class);
    SensorLocalSource sensorLocalSource;
    LoginRepository loginRepository;
    int messageId;

    private final OkHttpClient httpClient = new OkHttpClient();

    public interface UploadCallback {
        void onSuccess();
        void onFailure();
    }


    public SensorNetworkSource(Context applicationContext,
                               RequestQueue requestQueue,
                               SensorLocalSource sensorLocalSource,
                               LoginRepository loginRepository) {
        context = applicationContext;
        this.requestQueue = requestQueue;
        this.sensorLocalSource = sensorLocalSource;
        this.loginRepository = loginRepository;
    }

    public boolean sendPostRequestSync(String deviceId, String sessionId, byte[] compressedData, String hashedSensorData, String accessToken) throws IOException {
        String url = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.sensorloggeragent_update))
                .build().toString();

        String compressedDataString = Base64.encodeToString(compressedData, Base64.NO_WRAP);

        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("messageId", messageId++);
            postData.put("sessionId", sessionId);
            postData.put("compressedData", compressedDataString);
        } catch (JSONException e) {
            LOGGER.error("Error building payload", e);
            return false;
        }

        RequestBody body = RequestBody.create(
                postData.toString().getBytes(StandardCharsets.UTF_8),
                MediaType.parse("application/json; charset=utf-8"));

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                LOGGER.info("Upload succeeded: " + response.code() + ", messageId " + (messageId - 1)
                        + ", sessionId " + sessionId
                        + ", server response: " + (responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody));
                return true;
            } else if (response.code() == 401) {
                LOGGER.warn("Upload rejected: invalid/expired token — forcing refresh next attempt");
                loginRepository.invalidateAccessToken();
                return false;
            } else {
                LOGGER.error("Upload failed with status " + response.code());
                return false;
            }
        }
    }

    public boolean sendPostRequestSync(String deviceId, String sessionId, byte[] compressedData, JSONArray sensorData, String accessToken) throws IOException {
        String hashedSensorData = computeHash(sensorData.toString());
        return sendPostRequestSync(deviceId, sessionId, compressedData, hashedSensorData, accessToken);
    }

    /**
     * Sends a POST request to upload compressed sensor data to the server.
     * If the network request fails, the data is stored locally as unsent data for future retries.
     */
    public void sendPostRequest(String deviceId, String sessionId, byte[] compressedData, String hashedSensorData, String accessToken) throws UnsupportedEncodingException {
        String url = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.sensorloggeragent_update))
                .build().toString();

        String compressedDataString = Base64.encodeToString(compressedData, Base64.NO_WRAP);
        LOGGER.info("Size of compressed data string: " + compressedDataString.getBytes("UTF-8").length + " bytes");

        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("messageId", messageId++);
            postData.put("sessionId", sessionId);
            postData.put("compressedData", compressedDataString);
            LOGGER.info("Sending sensor data with metadata and compressed data.");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        final String requestBody = postData.toString();

        StringRequest postRequest = new StringRequest(Request.Method.POST, url,
                response -> LOGGER.info(response),
                error -> {
                    LOGGER.error("Failed to send data to the server: " + error.toString());
                    if (!sensorLocalSource.isDataInUnsentData(hashedSensorData)) {
                        UnsentData unsentData = new UnsentData();
                        unsentData.deviceId = deviceId;
                        unsentData.sessionId = sessionId;
                        unsentData.data = hashedSensorData;
                        unsentData.timestamp = System.currentTimeMillis();
                        unsentData.dataHash = hashedSensorData;
                        sensorLocalSource.insertUnsentData(unsentData);
                    } else {
                        LOGGER.info("Data already in UnsentData. Skipping insertion.");
                    }
                }
        ) {
            @Override
            public byte[] getBody() {
                return requestBody.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String getBodyContentType() {
                return "application/json; charset=utf-8";
            }

            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };

        requestQueue.add(postRequest);
    }

    public void sendPostRequest(String deviceId, String sessionId, byte[] compressedData, JSONArray sensorData, String accessToken) throws UnsupportedEncodingException {
        String hashedSensorData = computeHash(sensorData.toString());
        sendPostRequest(deviceId, sessionId, compressedData, hashedSensorData, accessToken);
    }

    public void resetMessageId() {
        messageId = 0;
    }

}