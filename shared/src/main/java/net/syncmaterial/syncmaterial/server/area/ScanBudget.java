package net.syncmaterial.syncmaterial.server.area;

/**
 * Per-tick work limits handed to a snapshot source. The source must stop starting new
 * containers/slots once either budget reaches zero and return a cursor for the remainder.
 */
public record ScanBudget(int maxContainers, int maxSlots) {
    public ScanBudget {
        if (maxContainers < 0) {
            throw new IllegalArgumentException("maxContainers must be >= 0");
        }
        if (maxSlots < 0) {
            throw new IllegalArgumentException("maxSlots must be >= 0");
        }
    }

    public static ScanBudget none() {
        return new ScanBudget(0, 0);
    }
}
