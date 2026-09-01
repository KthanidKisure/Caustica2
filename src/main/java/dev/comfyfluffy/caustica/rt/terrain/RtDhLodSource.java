package dev.comfyfluffy.caustica.rt.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility facade for the original DH-backed LOD call sites.
 *
 * <p>The renderer no longer depends on Distant Horizons at runtime. RtTerrain and RtDhLodRegion keep
 * their existing ABI while the data comes from {@link RtCausticaLodSource}, Caustica's own persistent
 * surface cache. Keeping this tiny bridge lets the renderer pivot safely without mixing a large rename
 * into the functional change; the old DH reflection/database code is intentionally gone.</p>
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
        return RtCausticaLodSource.available();
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

        RtCausticaLodSource.FetchResult source =
                RtCausticaLodSource.fetchArea(footprintBlocks, originBlockX, originBlockZ);
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
    }
}
