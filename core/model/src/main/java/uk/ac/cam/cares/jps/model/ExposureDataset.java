package uk.ac.cam.cares.jps.model;

public class ExposureDataset {
    private final String id;     // table name (used as exposure_table param)
    private final String label;  // same value, shown in dropdown

    public ExposureDataset(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}