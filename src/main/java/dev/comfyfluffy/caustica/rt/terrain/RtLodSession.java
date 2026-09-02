package dev.comfyfluffy.caustica.rt.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Locale;

/** Stable identity rules shared by every CausticaLOD persistence/source layer. */
final class RtLodSession {
    private RtLodSession() {
    }

    static String identity(Minecraft mc, ClientLevel level) {
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        return serverIdentity(mc) + "|" + level.dimension() + "|dv=" + dataVersion;
    }

    static boolean shouldSeedOfficialWynnLod(Minecraft mc, ClientLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension()) && isWynncraft(mc);
    }

    static boolean isWynncraft(Minecraft mc) {
        return isWynncraftHost(rawServer(mc));
    }

    static String serverIdentity(Minecraft mc) {
        String raw = rawServer(mc);
        if (raw.isEmpty()) {
            return singleplayerIdentity(mc);
        }
        if (isWynncraftHost(raw)) {
            // Wynncraft proxy/subdomain changes must not create duplicate native databases or re-import
            // the same official map. Dimension + Minecraft data version are appended by identity().
            return "wynncraft.com";
        }
        return raw;
    }

    /**
     * A bare "singleplayer" identity aliases every local save in the instance, which can make one
     * world's persistent distant terrain appear in another. Prefer the integrated server's save-folder
     * identity; fall back to its level name only if the storage path is temporarily unavailable.
     */
    private static String singleplayerIdentity(Minecraft mc) {
        try {
            MinecraftServer server = mc != null ? mc.getSingleplayerServer() : null;
            if (server != null) {
                Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                Path fileName = root.getFileName();
                if (fileName != null && !fileName.toString().isBlank()) {
                    return "singleplayer:" + fileName;
                }
                String levelName = server.getWorldData().getLevelName();
                if (levelName != null && !levelName.isBlank()) {
                    return "singleplayer:" + levelName;
                }
            }
        } catch (RuntimeException ignored) {
        }
        // Safe during the very small interval before the integrated server object becomes available;
        // the session is re-derived on later ticks. Never use this fallback to import WynnLOD.
        return "singleplayer:unresolved";
    }

    private static String rawServer(Minecraft mc) {
        try {
            if (mc != null && mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static boolean isWynncraftHost(String server) {
        String host = server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
        // Strip an ordinary host:port suffix. Bracketed IPv6 is intentionally left alone; Wynncraft's
        // DNS names are what we canonicalize and a literal IP should remain a distinct server identity.
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }
}
