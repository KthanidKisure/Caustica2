package dev.comfyfluffy.caustica.rt.terrain;

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
