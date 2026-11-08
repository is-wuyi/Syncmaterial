package net.syncmaterial.syncmaterial.server.storage;

import java.sql.SQLException;

/** Internal id-range checks shared by read and write paths. */
final class StoreIds {
    private StoreIds() {
    }

    static int checkedIntId(long id, String kind) throws SQLException {
        if (id <= 0) {
            throw new SQLException("invalid " + kind + " id: " + id);
        }
        if (id > Integer.MAX_VALUE) {
            throw new StorageException(kind + " id exceeds int range: " + id);
        }
        return (int) id;
    }
}
