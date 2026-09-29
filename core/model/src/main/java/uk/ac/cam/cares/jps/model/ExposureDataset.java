package uk.ac.cam.cares.jps.model;

public class ExposureDataset {
    private final String label;
    private final String iri;

    public ExposureDataset(String label, String iri) {
        this.label = label;
        this.iri = iri;
    }

    public String getLabel() { return label; }
    public String getIri() { return iri; }
    public String getDerivedTableName() {
        return label.trim().toLowerCase().replaceAll("\\s+", "_");
    }

    @Override
    public String toString() {
        return label; // shown in the dropdown
    }
}