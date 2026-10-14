package uk.ac.cam.cares.jps.network;

import android.content.Context;
import android.graphics.Bitmap;                          // NEW: raster comes back as a Bitmap
import android.widget.ImageView;                         // NEW: needed by Volley's ImageRequest constructor

import androidx.annotation.NonNull;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.ImageRequest;          // NEW
import com.android.volley.toolbox.StringRequest;

import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Locale;                                 // NEW: so decimals always use "." in the bbox string
import java.util.Map;

import okhttp3.HttpUrl;

/**
 * Network source for fetching an arbitrary layer from GeoServer via GeoServerJwtProxy.
 * Vector layers (points/lines/polygons) come back as GeoJSON via WFS.
 * Raster layers (NEW) come back as a rendered PNG via WMS.
 */
public class DatasetLayerNetworkSource {

    private static final Logger LOGGER = Logger.getLogger(DatasetLayerNetworkSource.class);

    // NEW: width of the PNG GeoServer renders. Height is worked out from the bbox aspect ratio.
    // Bigger = sharper when zoomed in, but more memory (about 12 MB for 2048 x 1500 pixels).
    private static final int RASTER_IMAGE_WIDTH_PX = 2048;
    // NEW: radius used by Web Mercator (EPSG:3857)
    private static final double EARTH_RADIUS_M = 6378137.0;

    private final RequestQueue requestQueue;
    private final Context context;

    public DatasetLayerNetworkSource(RequestQueue requestQueue, Context context) {
        this.requestQueue = requestQueue;
        this.context = context;
    }

    /**
     * Vector layer, unchanged from before.
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

    /**
     * NEW: raster layer. Asks GeoServer (WMS GetMap) to render the layer over the given
     * bounding box as a transparent PNG, using the SLD style published on the layer.
     *
     * @param accessToken Bearer token obtained from LoginRepository
     * @param layerName   fully-qualified layer name, e.g. "twa:ndvi_raster"
     * @param bbox        {minLon, minLat, maxLon, maxLat} in WGS84 degrees
     */
    public void getRasterImage(String accessToken, String layerName, double[] bbox,
                               Response.Listener<Bitmap> onSuccess, Response.ErrorListener onFailure) {
        // Convert the lon/lat box to Web Mercator metres. The map draws in Mercator, so asking
        // GeoServer for EPSG:3857 means the image lines up with the map exactly.
        double minX = lonToMercatorX(bbox[0]);
        double minY = latToMercatorY(bbox[1]);
        double maxX = lonToMercatorX(bbox[2]);
        double maxY = latToMercatorY(bbox[3]);

        // Keep the image's aspect ratio the same as the box, otherwise the picture is stretched
        int widthPx = RASTER_IMAGE_WIDTH_PX;
        int heightPx = (int) Math.round(widthPx * (maxY - minY) / (maxX - minX));

        // Locale.US so a device set to a comma-decimal locale doesn't break the URL
        String bboxParam = String.format(Locale.US, "%f,%f,%f,%f", minX, minY, maxX, maxY);

        String uri = HttpUrl.get(context.getString(uk.ac.cam.cares.jps.utils.R.string.host_with_port)).newBuilder()
                .addPathSegments(context.getString(uk.ac.cam.cares.jps.utils.R.string.geoserver_jwt_proxy_geoserver_twa_wms))
                .addQueryParameter("service", "WMS")
                .addQueryParameter("version", "1.1.1")        // 1.1.1 uses "srs" and x,y axis order, simplest
                .addQueryParameter("request", "GetMap")
                .addQueryParameter("layers", layerName)
                .addQueryParameter("styles", "")              // empty = use the layer's default SLD
                .addQueryParameter("format", "image/png")
                .addQueryParameter("transparent", "true")     // areas with no data stay see-through
                .addQueryParameter("srs", "EPSG:3857")
                .addQueryParameter("width", String.valueOf(widthPx))
                .addQueryParameter("height", String.valueOf(heightPx))
                .addQueryParameter("bbox", bboxParam)
                .build().toString();

        LOGGER.info("Fetching raster layer '" + layerName + "' via jwt proxy: " + uri);

        // ImageRequest downloads and decodes the PNG into a Bitmap. maxWidth/maxHeight = 0 means
        // "decode at full size". ARGB_8888 keeps the transparency and is the only config Mapbox
        // accepts. getHeaders() adds the same Bearer header your other requests send.
        ImageRequest request = new ImageRequest(uri, onSuccess, 0, 0,
                ImageView.ScaleType.CENTER_INSIDE, Bitmap.Config.ARGB_8888,
                error -> {
                    // If GeoServer returns an XML ServiceException (e.g. wrong layer name) with HTTP 200,
                    // Volley fails to decode it and lands here too.
                    LOGGER.error("Failed to fetch raster layer", error);
                    onFailure.onErrorResponse(error);
                }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };
        // Rendering a large raster server-side can be slow, so allow longer than the vector request
        request.setRetryPolicy(new DefaultRetryPolicy(30000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    // NEW: standard WGS84 -> Web Mercator formulas
    private static double lonToMercatorX(double lonDeg) {
        return EARTH_RADIUS_M * Math.toRadians(lonDeg);
    }

    private static double latToMercatorY(double latDeg) {
        return EARTH_RADIUS_M * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(latDeg) / 2.0));
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