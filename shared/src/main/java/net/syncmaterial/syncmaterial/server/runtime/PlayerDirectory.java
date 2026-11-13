package net.syncmaterial.syncmaterial.server.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.PlayerDirectoryEntry;
import net.syncmaterial.syncmaterial.core.PlayerIdentity;
import net.syncmaterial.syncmaterial.core.TeamLimits;

/** Merges already-known identities only; no lookup or synthetic offline UUID is performed. */
public final class PlayerDirectory {
    private PlayerDirectory() {}

    public static List<PlayerDirectoryEntry> merge(List<PlayerIdentity> stored, List<PlayerIdentity> cached,
                                                    List<PlayerIdentity> online) {
        Map<UUID, PlayerDirectoryEntry> resolved = new LinkedHashMap<>();
        Map<String, PlayerDirectoryEntry> unresolved = new LinkedHashMap<>();
        add(stored, false, resolved, unresolved);
        add(cached, false, resolved, unresolved);
        add(online, true, resolved, unresolved);
        for (PlayerDirectoryEntry entry : resolved.values()) {
            unresolved.remove(key(entry.player().name()));
        }
        java.util.HashSet<PlayerDirectoryEntry> seen = new java.util.HashSet<>();
        List<PlayerDirectoryEntry> known = new ArrayList<>();
        for (PlayerIdentity identity : stored) {
            PlayerDirectoryEntry entry = identity.hasUuid() ? resolved.remove(identity.uuid())
                    : unresolved.remove(key(identity.name()));
            if (entry != null && seen.add(entry)) known.add(entry);
        }
        List<PlayerDirectoryEntry> result = new ArrayList<>();
        resolved.values().stream().filter(PlayerDirectoryEntry::online).forEach(result::add);
        known.stream().filter(PlayerDirectoryEntry::online).forEach(result::add);
        known.stream().filter(entry -> !entry.online()).forEach(result::add);
        resolved.values().stream().filter(entry -> !entry.online()).forEach(result::add);
        result.addAll(unresolved.values());
        result = new ArrayList<>(result.subList(0, Math.min(result.size(), TeamLimits.DIRECTORY)));
        result.sort(Comparator.comparing(PlayerDirectoryEntry::online).reversed()
                .thenComparing(entry -> entry.player().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.player().uuid() == null ? "" : entry.player().uuid().toString()));
        return List.copyOf(result);
    }

    /** Online name wins; otherwise a same-name set of different UUIDs is rejected. */
    public static PlayerIdentity resolve(PlayerIdentity requested, List<PlayerDirectoryEntry> directory) {
        if (requested.hasUuid()) {
            for (PlayerDirectoryEntry entry : directory) {
                if (requested.uuid().equals(entry.player().uuid())) return entry.player();
            }
            throw new IllegalArgumentException("Unknown player UUID: " + requested.uuid());
        }
        List<PlayerDirectoryEntry> matches = directory.stream()
                .filter(entry -> entry.player().name().equalsIgnoreCase(requested.name())).toList();
        List<PlayerDirectoryEntry> online = matches.stream().filter(PlayerDirectoryEntry::online).toList();
        List<PlayerDirectoryEntry> candidates = online.isEmpty() ? matches : online;
        PlayerIdentity resolved = null;
        for (PlayerDirectoryEntry entry : candidates) {
            PlayerIdentity candidate = entry.player();
            if (resolved != null && candidate.hasUuid() && resolved.hasUuid()
                    && !candidate.uuid().equals(resolved.uuid())) {
                throw new IllegalArgumentException("Ambiguous player name: " + requested.name());
            }
            if (resolved == null || candidate.hasUuid()) resolved = candidate;
        }
        return resolved == null ? requested : resolved;
    }

    private static void add(List<PlayerIdentity> identities, boolean online,
                            Map<UUID, PlayerDirectoryEntry> resolved,
                            Map<String, PlayerDirectoryEntry> unresolved) {
        for (PlayerIdentity identity : identities) {
            if (identity.name().length() > TeamLimits.PLAYER_NAME_LENGTH) continue;
            var entry = new PlayerDirectoryEntry(identity, online);
            if (identity.hasUuid()) resolved.put(identity.uuid(), entry);
            else unresolved.put(key(identity.name()), entry);
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
