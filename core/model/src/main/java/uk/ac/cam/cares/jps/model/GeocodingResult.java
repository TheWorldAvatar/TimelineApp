package uk.ac.cam.cares.jps.model;

// NEW FILE
/**
 * A place chosen as a route start or end: either an address search result from ORS,
 * or a "dropped pin" created by tapping the map.
 */
public class GeocodingResult {
    private final String label;
    private final double longitude;
    private final double latitude;

    public GeocodingResult(String label, double longitude, double latitude) {
        this.label = label;
        this.longitude = longitude;
        this.latitude = latitude;
    }

    public String getLabel() { return label; }
    public double getLongitude() { return longitude; }
    public double getLatitude() { return latitude; }

    // The dropdown adapter and the text field display toString(), so return the label.
    @Override
    public String toString() { return label; }
}