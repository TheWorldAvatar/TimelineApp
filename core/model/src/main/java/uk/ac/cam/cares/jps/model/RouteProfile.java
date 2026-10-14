package uk.ac.cam.cares.jps.model;

// NEW FILE
public enum RouteProfile {
    // label shown to the user, ORS profile name sent to the API
    DRIVING("Driving", "driving-car"),
    CYCLING("Cycling", "cycling-regular"),
    WALKING("Walking", "foot-walking");

    private final String label;
    private final String orsValue;

    RouteProfile(String label, String orsValue) {
        this.label = label;
        this.orsValue = orsValue;
    }

    public String getOrsValue() { return orsValue; }

    // Same idea as CalcType.fromValue(): lets you restore a saved value later.
    public static RouteProfile fromOrsValue(String value) {
        for (RouteProfile p : values()) {
            if (p.orsValue.equals(value)) return p;
        }
        return null;
    }

    @Override
    public String toString() { return label; } // shown in dropdowns
}