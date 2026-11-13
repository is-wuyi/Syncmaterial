package net.syncmaterial.syncmaterial.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/** Bounded identity input, separate from storage and Minecraft's server-thread online snapshot. */
record PlayerDirectoryInput(List<PlayerIdentity> cached, List<PlayerIdentity> trusted, boolean limited) {
    static CompletableFuture<PlayerDirectoryInput> load(List<PlayerIdentity> online,
                                                        Supplier<List<PlayerIdentity>> knownSource,
                                                        BooleanSupplier open, Executor executor) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (!open.getAsBoolean()) throw new IllegalStateException("Team runtime is closed");
                List<PlayerIdentity> cached = List.copyOf(knownSource.get());
                List<PlayerIdentity> ordered = new ArrayList<>(online);
                ordered.addAll(cached);
                LinkedHashMap<String, PlayerIdentity> unique = new LinkedHashMap<>();
                for (PlayerIdentity player : ordered) {
                    String key = player.hasUuid() ? player.uuid().toString()
                            : "name:" + player.name().toLowerCase(Locale.ROOT);
                    unique.putIfAbsent(key, player);
                }
                List<PlayerIdentity> trusted = List.copyOf(unique.values()).subList(0,
                        Math.min(unique.size(), TeamLimits.DIRECTORY));
                return new PlayerDirectoryInput(cached, trusted, unique.size() > trusted.size());
            }, executor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
