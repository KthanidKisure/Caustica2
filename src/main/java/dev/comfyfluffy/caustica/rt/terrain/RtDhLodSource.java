package dev.comfyfluffy.caustica.rt.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility facade for the original distant-LOD call sites.
 *
 * <p>The renderer has no Distant Horizons runtime dependency. Imported Wynncraft data is read from
 * Caustica's own packed surface regions when available; otherwise the source is the native live cache
 * learned from already-loaded Minecraft chunks. Keeping this small facade avoids coupling the terrain
 * streamer and TLAS code to any particular persistence/import format.</p>
 */
public final class RtDhLodSource {
    private RtDhLodSource() {
    }

    /** One vertical run of one representative distant material. topY is exclusive. */
    public record LodBox(int blockX, int bottomY, int topY, int blockZ, int sizeXZ,
                         BlockState blockState, int blockLight, int skyLight) {
        public int heightBlocks() {
            return topY - bottomY;
        }
    }

    public record FetchResult(List<LodBox> boxes, boolean querySucceeded) {
        public FetchResult {
            boxes = List.copyOf(boxes);
        }
    }

    public static boolean available() {
        // Calling packed availability also starts the one-time WynnLOD conversion when the current
        // server is Wynncraft and LOD is enabled. It only schedules background work; no IO happens here.
        return RtCausticaLodPackedSource.available() || RtCausticaLodSource.available();
    }

    public static FetchResult fetchArea(int footprintBlocks, int originBlockX, int originBlockZ) {
        // The coarse cache is a replacement only for terrain that has left the full-resolution window.
        // Never let a virtual LOD section overlap current vanilla/RT chunks: apart from wasting BLAS
        // memory, duplicate surfaces cause z-fighting and make LOD warm-up look like a terrain failure.
        // A two-chunk pad gives the ordinary section streamer room for its neighbour-correct extraction.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int fullRadius = (Math.max(1, mc.options.getEffectiveRenderDistance()) + 2) * 16;
            int px = mc.player.getBlockX();
            int pz = mc.player.getBlockZ();
            long minX = originBlockX;
            long minZ = originBlockZ;
            long maxX = minX + Math.max(1, footprintBlocks) - 1L;
            long maxZ = minZ + Math.max(1, footprintBlocks) - 1L;
            if (maxX >= (long) px - fullRadius && minX <= (long) px + fullRadius
                    && maxZ >= (long) pz - fullRadius && minZ <= (long) pz + fullRadius) {
                // Retry rather than mark empty: after the player moves this same persistent region may
                // become distant and should become eligible immediately, not after the empty cooldown.
                return new FetchResult(List.of(), false);
            }
        }

        // A completed imported pack is authoritative for the far map and also checks live per-chunk
        // overrides first. Until that one-time conversion finishes, native capture keeps LOD usable.
        RtCausticaLodSource.FetchResult source = RtCausticaLodPackedSource.available()
                ? RtCausticaLodPackedSource.fetchArea(footprintBlocks, originBlockX, originBlockZ)
                : RtCausticaLodSource.fetchArea(footprintBlocks, originBlockX, originBlockZ);
        if (source.boxes().isEmpty()) {
            return new FetchResult(List.of(), source.querySucceeded());
        }
        ArrayList<LodBox> boxes = new ArrayList<>(source.boxes().size());
        for (RtCausticaLodSource.LodBox box : source.boxes()) {
            boxes.add(new LodBox(
                    box.blockX(), box.bottomY(), box.topY(), box.blockZ(), box.sizeXZ(),
                    box.blockState(), box.blockLight(), box.skyLight()));
        }
        return new FetchResult(boxes, source.querySucceeded());
    }

    public static void invalidate() {
        RtCausticaLodSource.invalidate();
        RtCausticaLodPackedSource.invalidate();
    }
}
