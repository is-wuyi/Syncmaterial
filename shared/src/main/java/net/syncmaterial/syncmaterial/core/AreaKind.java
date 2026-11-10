package net.syncmaterial.syncmaterial.core;

/** Area categories used by the storage schema and freshness reporting. */
public enum AreaKind {
    STAGING_AREA("staging_area"),
    WAREHOUSE("warehouse");

    private final String wireName;

    AreaKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static AreaKind fromWireName(String wireName) {
        for (AreaKind kind : values()) {
            if (kind.wireName.equals(wireName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown area kind: " + wireName);
    }
}
