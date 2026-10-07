package uk.ac.cam.cares.jps.model;

import java.util.ArrayList;
import java.util.List;

// NEW FILE
public class RouteOption {
    private final int index;              // position in the ORS response
    private final double distanceMeters;
    private final double durationSeconds;
    // Each entry is [longitude, latitude]. This is the order ORS returns and Mapbox's
    // Point.fromLngLat expects, so no swapping is needed when drawing.
    private final List<double[]> coordinates;
    private String label;                 // "Fastest", "Shortest", "Alternative 1"... set by the repository

    public RouteOption(int index, double distanceMeters, double durationSeconds, List<double[]> coordinates) {
        this.index = index;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.coordinates = new ArrayList<>(coordinates);
        this.label = "Route " + (index + 1); // default until labels are assigned
    }

    public int getIndex() { return index; }
    public double getDistanceMeters() { return distanceMeters; }
    public double getDurationSeconds() { return durationSeconds; }
    public List<double[]> getCoordinates() { return coordinates; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}