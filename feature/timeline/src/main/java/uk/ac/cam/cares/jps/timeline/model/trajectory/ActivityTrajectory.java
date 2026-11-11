package uk.ac.cam.cares.jps.timeline.model.trajectory;

import java.time.LocalDate;

/**
 * Raw GeoJSON of a day's activity trajectory. Features may contain activity_proportions,
 * which the map uses to determine a dominant activity for colouring. An empty string means
 * there is no data.
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
