package net.syncmaterial.syncmaterial.core;

/**
 * Typed project-view scope, mirroring the interest registry's {@code Scope(id, global)}
 * distinction. A global scope is a separate namespace even when its id string equals a
 * project id, so a global warehouse operation can never be mistaken for a project string.
 */
public record ProjectViewScope(String id, boolean global) {
    /** The single global warehouse-management scope used by the project-view envelope. */
    public static final String GLOBAL_WAREHOUSE_ID = "warehouses";

    public ProjectViewScope {
        ProjectViewLimits.requireBoundedText(id, "scope id", ProjectViewLimits.SCOPE_LENGTH, false);
    }

    public static ProjectViewScope project(String projectId) {
        return new ProjectViewScope(projectId, false);
    }

    public static ProjectViewScope global(String scopeId) {
        return new ProjectViewScope(scopeId, true);
    }

    public static ProjectViewScope globalWarehouse() {
        return global(GLOBAL_WAREHOUSE_ID);
    }

    public boolean isProject() {
        return !global;
    }

    public String projectId() {
        if (global) {
            throw new IllegalStateException("global scope has no project id");
        }
        return id;
    }

    @Override
    public String toString() {
        return (global ? "global:" : "project:") + id;
    }
}
