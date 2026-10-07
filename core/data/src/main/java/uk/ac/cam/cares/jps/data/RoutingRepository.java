package uk.ac.cam.cares.jps.data;

import com.android.volley.VolleyError;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import uk.ac.cam.cares.jps.model.GeocodingResult; 
import uk.ac.cam.cares.jps.model.RouteOption;
import uk.ac.cam.cares.jps.model.RouteProfile;
import uk.ac.cam.cares.jps.network.OrsRoutingNetworkSource;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

// NEW FILE
// Note: no LoginRepository here, because ORS doesn't use the Keycloak token.
public class RoutingRepository {
    private static final Logger LOGGER = Logger.getLogger(RoutingRepository.class);
    private static final int TARGET_ROUTE_COUNT = 3; // ask ORS for up to 3 routes

    private final OrsRoutingNetworkSource orsNetworkSource;

    public RoutingRepository(OrsRoutingNetworkSource orsNetworkSource) {
        this.orsNetworkSource = orsNetworkSource;
    }

    // The method a ViewModel will call. Same RepositoryCallback pattern as the rest of your app.
    public void getRoutes(RouteProfile profile,
                          double startLon, double startLat,
                          double endLon, double endLat,
                          RepositoryCallback<List<RouteOption>> callback) {
        orsNetworkSource.getRoutes(profile.getOrsValue(), startLon, startLat, endLon, endLat, TARGET_ROUTE_COUNT,
                response -> deliver(response, callback),
                error -> {
                    if (isBadRequest(error)) {
                        // ORS can answer 400 when alternatives are not possible (for example, it has
                        // been reported to refuse them for long routes, roughly over 100 km).
                        // Fall back to a plain single-route request instead of showing an error.
                        LOGGER.info("ORS rejected alternative routes, retrying for a single route");
                        orsNetworkSource.getRoutes(profile.getOrsValue(), startLon, startLat, endLon, endLat, null,
                                response -> deliver(response, callback),
                                error2 -> callback.onFailure(new Throwable("Failed to get routes", error2)));
                    } else {
                        callback.onFailure(new Throwable("Failed to get routes", error));
                    }
                });
    }

    private boolean isBadRequest(VolleyError error) {
        return error.networkResponse != null && error.networkResponse.statusCode == 400;
    }

    // Parses the response and reports success or failure through the callback.
    private void deliver(JSONObject response, RepositoryCallback<List<RouteOption>> callback) {
        try {
            List<RouteOption> routes = parseRoutes(response);
            if (routes.isEmpty()) {
                callback.onFailure(new Throwable("No route found"));
            } else {
                callback.onSuccess(routes);
            }
        } catch (JSONException e) {
            callback.onFailure(new Throwable("Unexpected routing response", e));
        }
    }

    /**
     * Parses an ORS /geojson response. It is a FeatureCollection with one Feature per route:
     *   features[i].properties.summary.distance (metres), .duration (seconds)
     *   features[i].geometry.coordinates = [[lon, lat], ...]
     * Package-private (not private) so it can be unit tested later.
     */
    static List<RouteOption> parseRoutes(JSONObject response) throws JSONException {
        List<RouteOption> routes = new ArrayList<>();
        JSONArray features = response.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject summary = feature.getJSONObject("properties").getJSONObject("summary");
            JSONArray coordinateArray = feature.getJSONObject("geometry").getJSONArray("coordinates");

            List<double[]> coordinates = new ArrayList<>();
            for (int j = 0; j < coordinateArray.length(); j++) {
                JSONArray pair = coordinateArray.getJSONArray(j);
                // Take only lon and lat; ORS may append elevation as a third value.
                coordinates.add(new double[]{pair.getDouble(0), pair.getDouble(1)});
            }

            routes.add(new RouteOption(i, summary.getDouble("distance"), summary.getDouble("duration"), coordinates));
        }

        assignLabels(routes);
        return routes;
    }

    // ORS doesn't label routes (Google does), so name them by their properties:
    // lowest duration = "Fastest", lowest distance = "Shortest", the rest = "Alternative n".
    // If one route is both fastest and shortest, it is labelled "Fastest".
    private static void assignLabels(List<RouteOption> routes) {
        if (routes.size() < 2) return; // a single route keeps the default "Route 1"

        RouteOption fastest = routes.get(0);
        RouteOption shortest = routes.get(0);
        for (RouteOption r : routes) {
            if (r.getDurationSeconds() < fastest.getDurationSeconds()) fastest = r;
            if (r.getDistanceMeters() < shortest.getDistanceMeters()) shortest = r;
        }

        int alternativeNumber = 1;
        for (RouteOption r : routes) {
            if (r == fastest) {
                r.setLabel("Fastest");
            } else if (r == shortest) {
                r.setLabel("Shortest");
            } else {
                r.setLabel("Alternative " + alternativeNumber++);
            }
        }
    }

    // Returns up to 5 suggestions, each with a label and a [lon, lat] position.
    public void searchPlaces(String text, Double focusLongitude, Double focusLatitude,
                             RepositoryCallback<List<GeocodingResult>> callback) {
        orsNetworkSource.searchPlaces(text, focusLongitude, focusLatitude, 5,
                response -> {
                    try {
                        callback.onSuccess(parsePlaces(response));
                    } catch (JSONException e) {
                        callback.onFailure(new Throwable("Unexpected geocoding response", e));
                    }
                },
                error -> callback.onFailure(new Throwable("Failed to search places", error)));
    }

    //   features[i].properties.label      = the display text, e.g. "Marina Bay Sands, Singapore"
    //   features[i].geometry.coordinates  = [lon, lat]
    static List<GeocodingResult> parsePlaces(JSONObject response) throws JSONException {
        List<GeocodingResult> places = new ArrayList<>();
        JSONArray features = response.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject properties = feature.getJSONObject("properties");
            JSONArray coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates");

            String label = properties.optString("label", properties.optString("name", "Unnamed place"));
            places.add(new GeocodingResult(label, coordinates.getDouble(0), coordinates.getDouble(1)));
        }
        return places;
    }
}