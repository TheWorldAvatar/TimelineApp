package uk.ac.cam.cares.jps.timeline.model.trajectory;

import java.time.LocalDate;

/**
 * Raw GeoJSON of a day's trajectory split by activity (every feature has activity_type).
 * Used only to colour the map by activity; an empty string means there is no data.
 */
public class ActivityTrajectory {
    private final LocalDate date;
    private final String geojson;

    public ActivityTrajectory(LocalDate date, String geojson) {
        this.date = date;
        this.geojson = geojson == null ? "" : geojson;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getGeojson() {
        return geojson;
    }
}
