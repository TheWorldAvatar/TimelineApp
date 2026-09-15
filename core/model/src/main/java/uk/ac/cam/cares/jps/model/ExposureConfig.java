package uk.ac.cam.cares.jps.model;

public class ExposureConfig {
    private String datasetId;       // key/id returned by your SQL-backed dataset list endpoint
    private String calculationType; // one of CalculationType enum names below
    private double distance;        // metres

    public ExposureConfig() {}

    public ExposureConfig(String datasetId, String calculationType, double distance) {
        this.datasetId = datasetId;
        this.calculationType = calculationType;
        this.distance = distance;
    }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public String getCalculationType() { return calculationType; }
    public void setCalculationType(String calculationType) { this.calculationType = calculationType; }

    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }
}