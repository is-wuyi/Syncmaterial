package net.syncmaterial.syncmaterial.server.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;

public final class LegacyDatabaseBackup {
    private LegacyDatabaseBackup() {}

    public static Path preserve(Path legacyDatabase, Path backupRoot) throws IOException {
        if (!Files.isRegularFile(legacyDatabase)) return null;
        // Copy closed legacy files without opening a JDBC connection or checkpointing their WAL.
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        Files.createDirectories(backupRoot);
        Path destination = Files.createTempDirectory(backupRoot, timestamp + "-");
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path source = legacyDatabase.resolveSibling(legacyDatabase.getFileName() + suffix);
            if (Files.isRegularFile(source)) {
                Files.copy(source, destination.resolve(source.getFileName()));
            }
        }
        return destination;
    }
}
