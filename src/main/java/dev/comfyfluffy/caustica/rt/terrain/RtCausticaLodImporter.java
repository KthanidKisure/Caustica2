package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.CausticaConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One-time WynnLOD importer for Caustica's native distant-terrain cache.
 *
 * <p>The WynnLOD release contains a Voxy database with full 32-cubed multiresolution sections. Caustica
 * opens that database read-only, decodes only level-0 sections, throws away caves/internal voxels, and
 * writes the visible surface into {@link RtCausticaLodRegionStore}. Rendering never loads Voxy or
 * Distant Horizons classes. The two small conversion libraries are isolated in a temporary child class
 * loader and are used only while an import is running.</p>
 *
 * <p>On Wynncraft, Caustica downloads the official WynnLOD Voxy archive, verifies its release SHA-256,
 * converts it, then deletes the extracted staging tree and archive. The converter is deliberately
 * bounded-memory: it indexes only horizontal section coordinates, point-reads one vertical column at a
 * time, and spills finished surface tiles into small per-region staging files. It never keeps the whole
 * Wynncraft surface in the Minecraft heap. The conversion runs on a minimum-priority daemon thread and
 * publishes every final region atomically.</p>
 */
final class RtCausticaLodImporter {
    private static final String WYNNLOD_URL =
            "https://github.com/DrBiznes/WynnLODGrabber/releases/download/LOD-04-19-26/frumavoxylods.zip";
    private static final String WYNNLOD_SHA256 =
            "2b494667869473c0133cc3de7c25b9b41b52382b1de79c7e79068139aad3f725";

    private static final String ROCKS_VERSION = "10.10.1.1";
    private static final String AIRCOMPRESSOR_VERSION = "3.7";
    private static final int VOXY_SECTION_EDGE = 32;
    private static final int VOXY_SECTION_VOLUME = VOXY_SECTION_EDGE * VOXY_SECTION_EDGE * VOXY_SECTION_EDGE;
    private static final int VOXY_MAPPING_BYTES = VOXY_SECTION_VOLUME * 2;
    private static final int VOXY_LUT_OFFSET = 16 + VOXY_MAPPING_BYTES;
    private static final int MAX_VOXY_RAW_BYTES = VOXY_LUT_OFFSET + VOXY_SECTION_VOLUME * 8;
    private static final long PROGRESS_STEP = 64L * 1024L * 1024L;
    // Reuse one connection pool for Maven sidecars/tools and the ~592 MiB WynnLOD archive instead of
    // constructing a fresh HttpClient/executor for every request in the one-time import.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "caustica-lod-import");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final long RETRY_DELAY_NANOS = 30_000_000_000L;
    private static volatile String retryIdentity = "";
    private static volatile long retryAfterNanos;

    private RtCausticaLodImporter() {
    }

    /** Called from the stable-world capture tick; all heavy work is immediately handed to WORKER. */
    static void tick(Minecraft mc, ClientLevel level, Path sessionRoot, String sessionIdentity) {
        if (!CausticaConfig.Rt.Lod.ENABLED.value() || sessionRoot == null || level == null) {
            return;
        }
        // The official WynnLOD archive describes the normal Wynncraft overworld only. Other
        // dimensions still use Caustica's live native capture, never a duplicate/wrong bulk import.
        if (!RtLodSession.shouldSeedOfficialWynnLod(mc, level)) {
            return;
        }
        Path marker = sessionRoot.resolve("wynnlod-v2.complete");
        if (completedPackMarkerValid(sessionRoot)) {
            return;
        }
        // A crash during an older/non-atomic marker write must never make an incomplete import look
        // authoritative on restart. Conversion itself is idempotent and atomically republishes regions.
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            CausticaMod.LOGGER.debug("CausticaLOD could not remove invalid WynnLOD completion marker {}: {}",
                    marker, e.toString());
        }
        long now = System.nanoTime();
        if (!sessionIdentity.equals(retryIdentity)) {
            retryIdentity = sessionIdentity;
            retryAfterNanos = 0L;
        }
        if (now < retryAfterNanos || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        WORKER.execute(() -> {
            try {
                importWynncraft(sessionRoot, marker);
                retryAfterNanos = Long.MAX_VALUE;
            } catch (Throwable t) {
                retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
                CausticaMod.LOGGER.error(
                        "CausticaLOD WynnLOD import failed; live native capture remains usable and import will retry", t);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** True only for a fully written marker describing exactly the importer format/source we understand. */
    static boolean completedPackMarkerValid(Path sessionRoot) {
        if (sessionRoot == null) {
            return false;
        }
        Path marker = sessionRoot.resolve("wynnlod-v2.complete");
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            String source = null;
            String sha = null;
            long sections = -1L;
            long tiles = -1L;
            long regions = -1L;
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split);
                String value = line.substring(split + 1).trim();
                switch (key) {
                    case "source" -> source = value;
                    case "sha256" -> sha = value;
                    case "sections" -> sections = Long.parseLong(value);
                    case "tiles" -> tiles = Long.parseLong(value);
                    case "regions" -> regions = Long.parseLong(value);
                    default -> { }
                }
            }
            return "wynnlod-2.2.0".equals(source)
                    && WYNNLOD_SHA256.equalsIgnoreCase(sha)
                    && sections > 0L && tiles > 0L && regions > 0L;
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }

    /** Atomically publish the completion record only after every packed region is durable/published. */
    private static void writeCompleteMarker(Path marker, ImportStats stats) throws IOException {
        String contents = "source=wynnlod-2.2.0\nsha256=" + WYNNLOD_SHA256
                + "\nsections=" + stats.sections + "\ntiles=" + stats.tiles + "\nregions=" + stats.regions + "\n";
        Path tmp = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.writeString(tmp, contents, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void importWynncraft(Path sessionRoot, Path marker) throws Exception {
        Files.createDirectories(sessionRoot);
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path tools = gameDir.resolve("caustica_lod").resolve("tools");
        Path importRoot = gameDir.resolve("caustica_lod").resolve("import").resolve("wynnlod-2.2.0");
        Files.createDirectories(tools);
        Files.createDirectories(importRoot);

        Path rocksJar = ensureRocksJar(tools);
        Path airJar = ensureMavenJar(
                "https://repo.maven.apache.org/maven2/io/airlift/aircompressor-v3/" + AIRCOMPRESSOR_VERSION
                        + "/aircompressor-v3-" + AIRCOMPRESSOR_VERSION + ".jar",
                tools.resolve("aircompressor-v3-" + AIRCOMPRESSOR_VERSION + ".jar"));

        try (RocksBridge bridge = new RocksBridge(rocksJar, airJar)) {
            // The official WynnLOD release is the deterministic bulk source. Never silently depend on
            // an installed Voxy database: it may be partial, stale, from another profile, or absent.
            Path downloadedArchive = importRoot.resolve("frumavoxylods.zip");
            Path staging = importRoot.resolve("staging");
            ensureDownload(URI.create(WYNNLOD_URL), downloadedArchive, "SHA-256", WYNNLOD_SHA256);
            Path extractedMarker = staging.resolve(".complete");
            boolean extractionMatches = Files.isRegularFile(extractedMarker)
                    && WYNNLOD_SHA256.equals(Files.readString(extractedMarker, StandardCharsets.US_ASCII).trim());
            if (!extractionMatches) {
                deleteTree(staging);
                Files.createDirectories(staging);
                CausticaMod.LOGGER.info("CausticaLOD extracting official WynnLOD Voxy archive for one-time conversion");
                extractZip(downloadedArchive, staging);
                Files.writeString(extractedMarker, WYNNLOD_SHA256, StandardCharsets.US_ASCII);
            }
            List<Path> databases = discoverDatabases(staging, bridge);

            if (databases.isEmpty()) {
                throw new IOException("No Voxy RocksDB containing world_sections + id_mappings was found");
            }

            Path database = largestDatabase(databases);
            CausticaMod.LOGGER.info("CausticaLOD importing WynnLOD database {} ({} candidate database(s))",
                    database, databases.size());
            ImportStats stats = convertDatabase(bridge, database, sessionRoot);
            writeCompleteMarker(marker, stats);
            RtCausticaLodPackedSource.markImportedPackReady(sessionRoot);
            CausticaMod.LOGGER.info(
                    "CausticaLOD WynnLOD import complete: {} level-0 sections -> {} surface tiles in {} packed regions; DH/Voxy are not required for rendering",
                    stats.sections, stats.tiles, stats.regions);

            deleteTree(staging);
            Files.deleteIfExists(downloadedArchive);
        }
    }

    private static ImportStats convertDatabase(RocksBridge bridge, Path database, Path sessionRoot) throws Exception {
        Path spoolRoot = sessionRoot.resolve(".wynnlod-spool");
        deleteTree(spoolRoot);
        deleteImportedRegions(sessionRoot);
        Files.createDirectories(spoolRoot);

        try (RocksBridge.Database db = bridge.open(database);
   RegionSpool spool = new RegionSpool(spoolRoot)) {
  BlockState[] states = decodeBlockMappings(bridge, db);

  // Voxy's RocksDB key order is excellent for level filtering but Y-major inside level 0. A
  // sequential value scan would therefore keep most horizontal columns alive at once. First
  // collect only their 64-bit X/Z identities (primitive set, no section payloads), then use
  // RocksDB's point-lookup path to process one complete vertical column at a time.
  LongOpenHashSet horizontalColumns = new LongOpenHashSet(131_072);
  int[] minSectionY = {Integer.MAX_VALUE};
  int[] maxSectionY = {Integer.MIN_VALUE};
  long[] candidateSections = {0L};
  bridge.forEachKey(db, "world_sections", keyBytes -> {
      if (keyBytes.length != Long.BYTES) {
          return;
      }
      long key = ByteBuffer.wrap(keyBytes).order(ByteOrder.BIG_ENDIAN).getLong();
      if (voxyLevel(key) != 0) {
          return;
      }
      int sx = voxyX(key);
      int sy = voxyY(key);
      int sz = voxyZ(key);
      horizontalColumns.add(packCoords(sx, sz));
      minSectionY[0] = Math.min(minSectionY[0], sy);
      maxSectionY[0] = Math.max(maxSectionY[0], sy);
      candidateSections[0]++;
  });
  if (horizontalColumns.isEmpty()) {
      throw new IOException("WynnLOD world_sections contains no level-0 Voxy sections");
  }

  long[] columns = horizontalColumns.toLongArray();
  java.util.Arrays.sort(columns);
  horizontalColumns.clear();
  CausticaMod.LOGGER.info(
          "CausticaLOD WynnLOD index: {} level-0 section records, {} horizontal columns, section Y {}..{}",
          candidateSections[0], columns.length, minSectionY[0], maxSectionY[0]);

  long successfulSections = 0L;
  long malformedSections = 0L;
  int usableTiles = 0;
  long startNanos = System.nanoTime();
  byte[] lookupKey = new byte[Long.BYTES];

  for (int columnIndex = 0; columnIndex < columns.length; columnIndex++) {
      long horizontal = columns[columnIndex];
      int sx = (int) (horizontal >> 32);
      int sz = (int) horizontal;
      int baseChunkX = sx * 2;
      int baseChunkZ = sz * 2;

      // Exactly four 16x16 chunks cover one 32x32 Voxy section footprint. Pre-seeding these
      // builders makes ingestSection allocation-free no matter how many vertical slabs exist.
      HashMap<Long, TileBuilder> tiles = new HashMap<>(8);
      for (int dx = 0; dx < 2; dx++) {
          for (int dz = 0; dz < 2; dz++) {
              int cx = baseChunkX + dx;
              int cz = baseChunkZ + dz;
              tiles.put(packCoords(cx, cz), new TileBuilder());
          }
      }

      for (int sy = maxSectionY[0]; sy >= minSectionY[0]; sy--) {
          long sectionKey = voxySectionKey(sx, sy, sz);
          writeLongBigEndian(sectionKey, lookupKey);
          byte[] compressed = bridge.get(db, "world_sections", lookupKey);
          if (compressed == null) {
              continue;
          }
          try {
              byte[] raw = bridge.decompress(compressed);
              ingestSection(sectionKey, raw, states, tiles);
              successfulSections++;
          } catch (Throwable t) {
              malformedSections++;
              if (malformedSections <= 8) {
                  CausticaMod.LOGGER.warn("Skipping malformed WynnLOD section 0x{}: {}",
                          Long.toUnsignedString(sectionKey, 16), t.toString());
              }
          }
          if (allGroundResolved(tiles)) {
              break;
          }
      }

      for (Map.Entry<Long, TileBuilder> entry : tiles.entrySet()) {
          RtCausticaLodRegionStore.TileData tile = entry.getValue().finish();
          if (tile == null) {
              continue;
          }
          int chunkX = (int) (entry.getKey() >> 32);
          int chunkZ = (int) (long) entry.getKey();
          spool.append(chunkX, chunkZ, tile);
          usableTiles++;
      }

      int done = columnIndex + 1;
      if ((done & 0x1ff) == 0 || done == columns.length) {
          CausticaMod.LOGGER.info(
                  "CausticaLOD WynnLOD conversion: {}/{} horizontal columns, {} surface tiles",
                  done, columns.length, usableTiles);
      }
  }

  int regionCount = spool.publish(sessionRoot);
  double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
  if (malformedSections != 0L) {
      CausticaMod.LOGGER.warn("CausticaLOD WynnLOD conversion skipped {} malformed section(s)", malformedSections);
  }
  CausticaMod.LOGGER.info(
          "CausticaLOD WynnLOD bounded conversion finished in {} s; peak tile working set is one Voxy column plus one packed region",
          String.format(Locale.ROOT, "%.1f", seconds));
  return new ImportStats(successfulSections, usableTiles, regionCount);
        } finally {
  deleteTree(spoolRoot);
        }
    }

    private static boolean allGroundResolved(HashMap<Long, TileBuilder> tiles) {
        for (TileBuilder tile : tiles.values()) {
  if (!tile.complete()) {
      return false;
  }
        }
        return true;
    }

    private static BlockState[] decodeBlockMappings(RocksBridge bridge, RocksBridge.Database db) throws Exception {
        HashMap<Integer, BlockState> decoded = new HashMap<>();
        decoded.put(0, Blocks.AIR.defaultBlockState());
        int[] maxId = {0};
        int[] failures = {0};
        bridge.forEach(db, "id_mappings", (keyBytes, value) -> {
            if (keyBytes.length != Integer.BYTES) {
                return;
            }
            int key = ByteBuffer.wrap(keyBytes).order(ByteOrder.BIG_ENDIAN).getInt();
            int type = key >>> 30;
            if (type != 1) {
                return;
            }
            int id = key & 0x3fff_ffff;
            try {
                BlockState state = decodeBlockState(value);
                decoded.put(id, state);
                maxId[0] = Math.max(maxId[0], id);
            } catch (Throwable t) {
                failures[0]++;
                if (failures[0] <= 8) {
                    CausticaMod.LOGGER.warn("Unable to decode WynnLOD block-state mapping {}: {}", id, t.toString());
                }
            }
        });
        BlockState[] states = new BlockState[Math.max(1, maxId[0] + 1)];
        decoded.forEach((id, state) -> states[id] = state);
        states[0] = Blocks.AIR.defaultBlockState();
        CausticaMod.LOGGER.info("CausticaLOD decoded {} WynnLOD block-state mappings ({} failed)", decoded.size(), failures[0]);
        return states;
    }

    private static BlockState decodeBlockState(byte[] bytes) throws IOException {
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        CompoundTag stateTag = root.getCompound("block_state").orElseThrow();
        try {
            return BlockState.CODEC.parse(NbtOps.INSTANCE, stateTag).getOrThrow();
        } catch (RuntimeException original) {
            Dynamic<?> updated = DataFixers.getDataFixer().update(
                    References.BLOCK_STATE,
                    new Dynamic<>(NbtOps.INSTANCE, stateTag),
                    0,
                    SharedConstants.getCurrentVersion().dataVersion().version());
            try {
                return BlockState.CODEC.parse(NbtOps.INSTANCE, (net.minecraft.nbt.Tag) updated.getValue()).getOrThrow();
            } catch (RuntimeException failed) {
                failed.addSuppressed(original);
                throw failed;
            }
        }
    }

    private static void ingestSection(long key, byte[] raw, BlockState[] states,
                                      HashMap<Long, TileBuilder> tiles) throws IOException {
        if (raw.length < VOXY_LUT_OFFSET) {
            throw new IOException("Voxy section is only " + raw.length + " bytes");
        }
        ByteBuffer data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        long embeddedKey = data.getLong(0);
        if (embeddedKey != key) {
            throw new IOException("Voxy section key mismatch");
        }
        long metadata = data.getLong(8);
        int lutCount = (int) (metadata & 0xffffL);
        long required = (long) VOXY_LUT_OFFSET + (long) lutCount * Long.BYTES;
        if (lutCount <= 0 || required > raw.length) {
            throw new IOException("Invalid Voxy LUT size " + lutCount);
        }

        int sectionX = voxyX(key);
        int sectionY = voxyY(key);
        int sectionZ = voxyZ(key);
        int baseX = sectionX * VOXY_SECTION_EDGE;
        int baseY = sectionY * VOXY_SECTION_EDGE;
        int baseZ = sectionZ * VOXY_SECTION_EDGE;

        for (int lx = 0; lx < VOXY_SECTION_EDGE; lx++) {
            int worldX = baseX + lx;
            int chunkX = Math.floorDiv(worldX, RtCausticaLodRegionStore.TILE_EDGE);
            int tileX = Math.floorMod(worldX, RtCausticaLodRegionStore.TILE_EDGE);
            for (int lz = 0; lz < VOXY_SECTION_EDGE; lz++) {
                int worldZ = baseZ + lz;
                int chunkZ = Math.floorDiv(worldZ, RtCausticaLodRegionStore.TILE_EDGE);
                int tileZ = Math.floorMod(worldZ, RtCausticaLodRegionStore.TILE_EDGE);
                TileBuilder tile = tiles.computeIfAbsent(packCoords(chunkX, chunkZ), ignored -> new TileBuilder());
                int column = tileX * RtCausticaLodRegionStore.TILE_EDGE + tileZ;

                BlockState highest = null;
                BlockState ground = null;
                BlockState body = null;
                int highestY = Integer.MIN_VALUE;
                int groundY = Integer.MIN_VALUE;
                for (int ly = VOXY_SECTION_EDGE - 1; ly >= 0; ly--) {
                    int linear = lx | (lz << 5) | (ly << 10);
                    int mappingOffset = 16 + linear * 2;
                    int lutIndex = Short.toUnsignedInt(data.getShort(mappingOffset));
                    if (lutIndex >= lutCount) {
                        throw new IOException("Voxy LUT index " + lutIndex + " >= " + lutCount);
                    }
                    long voxel = data.getLong(VOXY_LUT_OFFSET + lutIndex * Long.BYTES);
                    int blockId = (int) ((voxel >>> 27) & 0xfffffL);
                    if (blockId == 0) {
                        continue;
                    }
                    BlockState state = stateFor(states, blockId);
                    int worldY = baseY + ly;
                    if (highest == null) {
                        highest = state;
                        highestY = worldY;
                    }
                    if (ground == null && structural(state)) {
                        ground = state;
                        groundY = worldY;
                    } else if (ground != null && body == null && structural(state)) {
                        body = state;
                        break;
                    }
                }

                if (highest != null) {
                    tile.offerSurface(column, highestY, Block.getId(highest));
                }
                if (ground != null) {
                    tile.offerGround(column, groundY, Block.getId(ground), Block.getId(body != null ? body : ground));
                }
            }
        }
    }

    private static boolean structural(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !(state.getBlock() instanceof LeavesBlock);
    }

    private static BlockState stateFor(BlockState[] states, int blockId) {
        if (blockId >= 0 && blockId < states.length && states[blockId] != null) {
            return states[blockId];
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static List<Path> discoverDatabases(Path root, RocksBridge bridge) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        Set<Path> candidates = new HashSet<>();
        try (var walk = Files.walk(root, 10)) {
            walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("CURRENT"))
                    .map(Path::getParent)
                    .forEach(candidates::add);
        }
        ArrayList<Path> valid = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                if (bridge.hasRequiredColumnFamilies(candidate)) {
                    valid.add(candidate);
                }
            } catch (Throwable t) {
                CausticaMod.LOGGER.debug("Ignoring non-Voxy RocksDB {}: {}", candidate, t.toString());
            }
        }
        return valid;
    }

    private static Path largestDatabase(List<Path> databases) throws IOException {
        Path best = databases.get(0);
        long bestSize = -1L;
        for (Path database : databases) {
            long size = directorySize(database);
            if (size > bestSize) {
                best = database;
                bestSize = size;
            }
        }
        return best;
    }

    private static long directorySize(Path root) throws IOException {
        long[] total = {0L};
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    total[0] += Files.size(path);
                } catch (IOException ignored) {
                }
            });
        }
        return total[0];
    }

    private static Path ensureRocksJar(Path tools) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String classifier;
        if (os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            classifier = "win64";
        } else if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            classifier = "linux64";
        } else if (os.contains("mac")) {
            classifier = "osx";
        } else {
            throw new IOException("No WynnLOD RocksDB converter binary for " + os + " / " + arch);
        }
        String name = "rocksdbjni-" + ROCKS_VERSION + "-" + classifier + ".jar";
        return ensureMavenJar(
                "https://repo.maven.apache.org/maven2/org/rocksdb/rocksdbjni/" + ROCKS_VERSION + "/" + name,
                tools.resolve(name));
    }

    /** Verify Maven Central SHA-256 sidecars before loading a downloaded converter JAR. */
    private static Path ensureMavenJar(String url, Path target) throws Exception {
        String expected = downloadText(URI.create(url + ".sha256")).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        ensureDownload(URI.create(url), target, "SHA-256", expected);
        return target;
    }

    private static void ensureDownload(URI uri, Path target, String algorithm, String expectedHex) throws Exception {
        if (Files.isRegularFile(target) && digest(target, algorithm).equalsIgnoreCase(expectedHex)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long resumeOffset = Files.isRegularFile(part) ? Files.size(part) : 0L;

        // At most one retry is needed here: a server may ignore/reject Range, in which case we fall back
        // to a clean full request. Genuine network failures keep the .part file for the importer's normal
        // 30-second retry rather than throwing away hundreds of already-downloaded MiB.
        for (int requestAttempt = 0; requestAttempt < 2; requestAttempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofHours(2))
                    .header("User-Agent", "CausticaLOD/0.1");
            if (resumeOffset > 0L) {
                request.header("Range", "bytes=" + resumeOffset + "-");
            }
            HttpResponse<InputStream> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            boolean append = resumeOffset > 0L && status == 206;

            if (resumeOffset > 0L && status == 416) {
                response.body().close();
                // A completed .part can produce 416 (range starts at EOF); verify before discarding it.
                if (digest(part, algorithm).equalsIgnoreCase(expectedHex)) {
                    publishDownload(part, target);
                    return;
                }
                Files.deleteIfExists(part);
                resumeOffset = 0L;
                continue;
            }
            if (status / 100 != 2) {
                response.body().close();
                throw new IOException("HTTP " + status + " downloading " + uri);
            }
            if (append) {
                String range = response.headers().firstValue("Content-Range").orElse("");
                if (!range.startsWith("bytes " + resumeOffset + "-")) {
                    response.body().close();
                    Files.deleteIfExists(part);
                    resumeOffset = 0L;
                    continue;
                }
            } else if (resumeOffset > 0L) {
                // A 2xx response other than 206 ignored our Range header. Its body is the complete file,
                // so safely replace the partial rather than appending duplicate bytes.
                resumeOffset = 0L;
            }

            long responseBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            long expectedTotal = responseBytes >= 0L ? resumeOffset + responseBytes : -1L;
            if (append) {
                CausticaMod.LOGGER.info("CausticaLOD resuming {} from {} MiB{}", uri,
                        resumeOffset / (1024 * 1024),
                        expectedTotal > 0L ? " (about " + expectedTotal / (1024 * 1024) + " MiB total)" : "");
            } else {
                CausticaMod.LOGGER.info("CausticaLOD downloading {}{}", uri,
                        responseBytes > 0L ? " (" + responseBytes / (1024 * 1024) + " MiB)" : "");
            }

            long total = resumeOffset;
            long nextProgress = ((total / PROGRESS_STEP) + 1L) * PROGRESS_STEP;
            var rawOut = append
                    ? Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    : Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try (InputStream in = new BufferedInputStream(response.body(), 1 << 20);
                 var out = new BufferedOutputStream(rawOut, 1 << 20)) {
                byte[] buffer = new byte[1 << 20];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    out.write(buffer, 0, read);
                    total += read;
                    if (total >= nextProgress) {
                        CausticaMod.LOGGER.info("CausticaLOD download progress: {} MiB", total / (1024 * 1024));
                        nextProgress += PROGRESS_STEP;
                    }
                }
            }
            if (expectedTotal >= 0L && total != expectedTotal) {
                // Keep the partial for a Range retry. This is different from a completed-but-wrong digest.
                throw new IOException("Incomplete download for " + uri + ": expected " + expectedTotal
                        + " bytes, received " + total);
            }

            String actual = digest(part, algorithm);
            if (!actual.equalsIgnoreCase(expectedHex)) {
                Files.deleteIfExists(part);
                throw new IOException("Digest mismatch for " + uri + ": expected " + expectedHex + ", got " + actual);
            }
            publishDownload(part, target);
            return;
        }
        throw new IOException("Unable to restart download after Range negotiation failed for " + uri);
    }

    private static void publishDownload(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String downloadText(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "CausticaLOD/0.1")
                .GET().build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " downloading " + uri);
        }
        return response.body();
    }

    private static HttpClient http() {
        return HTTP;
    }

    private static String digest(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 20)) {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read != 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void extractZip(Path archive, Path targetRoot) throws IOException {
        Path normalizedRoot = targetRoot.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive), 1 << 20))) {
            ZipEntry entry;
            byte[] buffer = new byte[1 << 20];
            while ((entry = zip.getNextEntry()) != null) {
                Path target = normalizedRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IOException("Unsafe WynnLOD zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 20)) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            if (read != 0) {
                                out.write(buffer, 0, read);
                            }
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String currentServer(Minecraft mc) {
        try {
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static boolean isWynncraft(String server) {
        String host = server;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }

    private static long packCoords(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private static long voxySectionKey(int x, int y, int z) {
        return ((long) (y & 0xff) << 52)
                | ((long) (z & 0x00ff_ffff) << 28)
                | ((long) (x & 0x00ff_ffff) << 4);
    }

    private static void writeLongBigEndian(long value, byte[] out) {
        for (int i = 0; i < Long.BYTES; i++) {
            out[i] = (byte) (value >>> (56 - i * 8));
        }
    }

    /** Remove only partial imported region packs. Live .clod captures are a separate namespace. */
    private static void deleteImportedRegions(Path sessionRoot) throws IOException {
        RtCausticaLodRegionStore.reset();
        if (!Files.isDirectory(sessionRoot)) {
            return;
        }
        try (var files = Files.list(sessionRoot)) {
            files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("r.") && (name.endsWith(".clodr") || name.endsWith(".clodr.tmp"));
            }).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }


    private static int voxyLevel(long key) {
        return (int) ((key >>> 60) & 0xfL);
    }

    private static int voxyX(long key) {
        return (int) ((key << 36) >> 40);
    }

    private static int voxyY(long key) {
        return (int) ((key << 4) >> 56);
    }

    private static int voxyZ(long key) {
        return (int) ((key << 12) >> 40);
    }

    private record ImportStats(long sections, int tiles, int regions) {
    }

    /** Accumulates vertical Voxy sections into the surface-only representation of one Minecraft chunk. */
    private static final class TileBuilder {
        final short[] groundY = new short[RtCausticaLodRegionStore.TILE_COLUMNS];
        final short[] surfaceY = new short[RtCausticaLodRegionStore.TILE_COLUMNS];
        final int[] groundStateId = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
        final int[] bodyStateId = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
        final int[] surfaceStateId = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
        int resolvedGroundColumns;

        TileBuilder() {
            java.util.Arrays.fill(groundY, RtCausticaLodRegionStore.NO_HEIGHT);
            java.util.Arrays.fill(surfaceY, RtCausticaLodRegionStore.NO_HEIGHT);
        }

        void offerSurface(int column, int y, int stateId) {
            if (surfaceY[column] == RtCausticaLodRegionStore.NO_HEIGHT || y > surfaceY[column]) {
                surfaceY[column] = clampHeight(y);
                surfaceStateId[column] = stateId;
            }
        }

        void offerGround(int column, int y, int groundId, int bodyId) {
            if (groundY[column] == RtCausticaLodRegionStore.NO_HEIGHT || y > groundY[column]) {
                if (groundY[column] == RtCausticaLodRegionStore.NO_HEIGHT) {
                    resolvedGroundColumns++;
                }
                groundY[column] = clampHeight(y);
                groundStateId[column] = groundId;
                bodyStateId[column] = bodyId;
            }
        }

        boolean complete() {
            return resolvedGroundColumns == RtCausticaLodRegionStore.TILE_COLUMNS;
        }

        RtCausticaLodRegionStore.TileData finish() {
            boolean any = false;
            for (int i = 0; i < groundY.length; i++) {
                if (groundY[i] == RtCausticaLodRegionStore.NO_HEIGHT) {
                    continue;
                }
                any = true;
                if (surfaceY[i] == RtCausticaLodRegionStore.NO_HEIGHT || surfaceY[i] < groundY[i]) {
                    surfaceY[i] = groundY[i];
                    surfaceStateId[i] = groundStateId[i];
                }
                if (bodyStateId[i] == 0) {
                    bodyStateId[i] = groundStateId[i];
                }
            }
            return any ? new RtCausticaLodRegionStore.TileData(
                    groundY, surfaceY, groundStateId, bodyStateId, surfaceStateId) : null;
        }

        private static short clampHeight(int y) {
            return (short) Math.clamp(y, Short.MIN_VALUE + 1, Short.MAX_VALUE);
        }
    }

    /**
     * Small disk-backed staging area. At most 32 append streams and one 32x32-chunk region payload are
     * resident at once, so conversion memory does not scale with Wynncraft's map area.
     */
    private static final class RegionSpool implements AutoCloseable {
        private static final int MAX_OPEN_STREAMS = 32;
        private final Path root;
        private final Set<Long> regionKeys = new HashSet<>();
        private final LinkedHashMap<Long, DataOutputStream> streams = new LinkedHashMap<>(32, 0.75f, true);

        RegionSpool(Path root) {
  this.root = root;
        }

        void append(int chunkX, int chunkZ, RtCausticaLodRegionStore.TileData tile) throws IOException {
  int rx = RtCausticaLodRegionStore.regionX(chunkX);
  int rz = RtCausticaLodRegionStore.regionZ(chunkZ);
  long regionKey = packCoords(rx, rz);
  regionKeys.add(regionKey);
  DataOutputStream out = stream(regionKey, rx, rz);
  out.writeInt(RtCausticaLodRegionStore.slot(chunkX, chunkZ));
  writeTile(out, tile);
        }

        int publish(Path sessionRoot) throws IOException {
  closeStreams();
  ArrayList<Long> keys = new ArrayList<>(regionKeys);
  keys.sort(Comparator.comparingLong(Long::longValue));
  int count = 0;
  for (long regionKey : keys) {
      int rx = (int) (regionKey >> 32);
      int rz = (int) regionKey;
      HashMap<Integer, RtCausticaLodRegionStore.TileData> tiles = new HashMap<>(1024);
      Path path = spoolPath(rx, rz);
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
          while (true) {
              final int slot;
              try {
                  slot = in.readInt();
              } catch (EOFException done) {
                  break;
              }
              if (slot < 0 || slot >= 1024) {
                  throw new IOException("Invalid CausticaLOD spool slot " + slot + " in " + path);
              }
              RtCausticaLodRegionStore.TileData previous = tiles.put(slot, readTile(in));
              if (previous != null) {
                  throw new IOException("Duplicate CausticaLOD spool slot " + slot + " in " + path);
              }
          }
      }
      RtCausticaLodRegionStore.writeRegion(sessionRoot, rx, rz, tiles);
      Files.deleteIfExists(path);
      count++;
      if ((count & 31) == 0 || count == keys.size()) {
          CausticaMod.LOGGER.info("CausticaLOD WynnLOD pack publish: {}/{} regions", count, keys.size());
      }
  }
  return count;
        }

        private DataOutputStream stream(long key, int rx, int rz) throws IOException {
  DataOutputStream current = streams.get(key);
  if (current != null) {
      return current;
  }
  if (streams.size() >= MAX_OPEN_STREAMS) {
      Map.Entry<Long, DataOutputStream> eldest = streams.entrySet().iterator().next();
      eldest.getValue().close();
      streams.remove(eldest.getKey());
  }
  DataOutputStream opened = new DataOutputStream(new BufferedOutputStream(
          Files.newOutputStream(spoolPath(rx, rz), StandardOpenOption.CREATE, StandardOpenOption.APPEND), 1 << 16));
  streams.put(key, opened);
  return opened;
        }

        private Path spoolPath(int rx, int rz) {
  return root.resolve("r." + rx + "." + rz + ".spool");
        }

        private static void writeTile(DataOutputStream out, RtCausticaLodRegionStore.TileData tile) throws IOException {
  for (int i = 0; i < RtCausticaLodRegionStore.TILE_COLUMNS; i++) {
      out.writeShort(tile.groundY()[i]);
      out.writeShort(tile.surfaceY()[i]);
      out.writeInt(tile.groundStateId()[i]);
      out.writeInt(tile.bodyStateId()[i]);
      out.writeInt(tile.surfaceStateId()[i]);
  }
        }

        private static RtCausticaLodRegionStore.TileData readTile(DataInputStream in) throws IOException {
  short[] groundY = new short[RtCausticaLodRegionStore.TILE_COLUMNS];
  short[] surfaceY = new short[RtCausticaLodRegionStore.TILE_COLUMNS];
  int[] groundState = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
  int[] bodyState = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
  int[] surfaceState = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
  for (int i = 0; i < RtCausticaLodRegionStore.TILE_COLUMNS; i++) {
      groundY[i] = in.readShort();
      surfaceY[i] = in.readShort();
      groundState[i] = in.readInt();
      bodyState[i] = in.readInt();
      surfaceState[i] = in.readInt();
  }
  return new RtCausticaLodRegionStore.TileData(groundY, surfaceY, groundState, bodyState, surfaceState);
        }

        private void closeStreams() throws IOException {
  IOException failure = null;
  for (DataOutputStream out : streams.values()) {
      try {
          out.close();
      } catch (IOException e) {
          if (failure == null) failure = e; else failure.addSuppressed(e);
      }
  }
  streams.clear();
  if (failure != null) {
      throw failure;
  }
        }

        @Override
        public void close() throws IOException {
  closeStreams();
        }
    }

    /** Reflection boundary around optional conversion libraries, keeping them out of Caustica's runtime ABI. */
    private static final class RocksBridge implements AutoCloseable {
        private final URLClassLoader loader;
        private final Class<?> rocksClass;
        private final Class<?> optionsClass;
        private final Class<?> dbOptionsClass;
        private final Class<?> cfOptionsClass;
        private final Class<?> cfDescriptorClass;
        private final Class<?> cfHandleClass;
        private final Method listColumnFamilies;
        private final Method openReadOnly;
        private final Method newIterator;
        private final Method iteratorSeekToFirst;
        private final Method iteratorIsValid;
        private final Method iteratorKey;
        private final Method iteratorValue;
        private final Method iteratorNext;
        private final Method get;
        private final Object zstd;
        private final Method zstdSize;
        private final Method zstdDecompress;

        RocksBridge(Path rocksJar, Path airJar) throws Exception {
            this.loader = new URLClassLoader(new URL[]{rocksJar.toUri().toURL(), airJar.toUri().toURL()},
                    RtCausticaLodImporter.class.getClassLoader());
            this.rocksClass = Class.forName("org.rocksdb.RocksDB", true, loader);
            this.optionsClass = Class.forName("org.rocksdb.Options", true, loader);
            this.dbOptionsClass = Class.forName("org.rocksdb.DBOptions", true, loader);
            this.cfOptionsClass = Class.forName("org.rocksdb.ColumnFamilyOptions", true, loader);
            this.cfDescriptorClass = Class.forName("org.rocksdb.ColumnFamilyDescriptor", true, loader);
            this.cfHandleClass = Class.forName("org.rocksdb.ColumnFamilyHandle", true, loader);
            Class<?> iteratorClass = Class.forName("org.rocksdb.RocksIterator", true, loader);
            rocksClass.getMethod("loadLibrary").invoke(null);
            this.listColumnFamilies = rocksClass.getMethod("listColumnFamilies", optionsClass, String.class);
            this.openReadOnly = rocksClass.getMethod("openReadOnly", dbOptionsClass, String.class, List.class, List.class);
            this.newIterator = rocksClass.getMethod("newIterator", cfHandleClass);
            this.iteratorSeekToFirst = iteratorClass.getMethod("seekToFirst");
            this.iteratorIsValid = iteratorClass.getMethod("isValid");
            this.iteratorKey = iteratorClass.getMethod("key");
            this.iteratorValue = iteratorClass.getMethod("value");
            this.iteratorNext = iteratorClass.getMethod("next");
            this.get = rocksClass.getMethod("get", cfHandleClass, byte[].class);

            Class<?> zstdClass = Class.forName("io.airlift.compress.v3.zstd.ZstdJavaDecompressor", true, loader);
            this.zstd = zstdClass.getConstructor().newInstance();
            this.zstdSize = zstdClass.getMethod("getDecompressedSize", byte[].class, int.class, int.class);
            this.zstdDecompress = zstdClass.getMethod("decompress",
                    byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        }

        boolean hasRequiredColumnFamilies(Path path) throws Exception {
            Set<String> names = new HashSet<>();
            try (AutoCloseable options = (AutoCloseable) optionsClass.getConstructor().newInstance()) {
                @SuppressWarnings("unchecked")
                List<byte[]> columns = (List<byte[]>) listColumnFamilies.invoke(null, options, path.toString());
                for (byte[] name : columns) {
                    names.add(new String(name, StandardCharsets.UTF_8));
                }
            }
            return names.contains("world_sections") && names.contains("id_mappings");
        }

        Database open(Path path) throws Exception {
            AutoCloseable options = (AutoCloseable) optionsClass.getConstructor().newInstance();
            @SuppressWarnings("unchecked")
            List<byte[]> names = (List<byte[]>) listColumnFamilies.invoke(null, options, path.toString());
            options.close();

            Constructor<?> cfOptionsCtor = cfOptionsClass.getConstructor();
            Constructor<?> descriptorCtor = cfDescriptorClass.getConstructor(byte[].class, cfOptionsClass);
            ArrayList<Object> descriptors = new ArrayList<>(names.size());
            ArrayList<AutoCloseable> cfOptions = new ArrayList<>(names.size());
            for (byte[] name : names) {
                Object cfOption = cfOptionsCtor.newInstance();
                cfOptions.add((AutoCloseable) cfOption);
                descriptors.add(descriptorCtor.newInstance(name, cfOption));
            }

            Object dbOptions = dbOptionsClass.getConstructor().newInstance();
            dbOptionsClass.getMethod("setCreateIfMissing", boolean.class).invoke(dbOptions, false);
            ArrayList<Object> handles = new ArrayList<>();
            Object db = openReadOnly.invoke(null, dbOptions, path.toString(), descriptors, handles);
            HashMap<String, Object> byName = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                byName.put(new String(names.get(i), StandardCharsets.UTF_8), handles.get(i));
            }
            return new Database((AutoCloseable) db, (AutoCloseable) dbOptions, cfOptions, handles, byName);
        }

        void forEachKey(Database db, String columnFamily, KeyConsumer consumer) throws Exception {
  Object handle = db.handlesByName.get(columnFamily);
  if (handle == null) {
      throw new IOException("Voxy database lacks column family " + columnFamily);
  }
  Object iterator = newIterator.invoke(db.db, handle);
  try {
      iteratorSeekToFirst.invoke(iterator);
      while ((boolean) iteratorIsValid.invoke(iterator)) {
          consumer.accept((byte[]) iteratorKey.invoke(iterator));
          iteratorNext.invoke(iterator);
      }
  } finally {
      ((AutoCloseable) iterator).close();
  }
        }

        byte[] get(Database db, String columnFamily, byte[] key) throws Exception {
  Object handle = db.handlesByName.get(columnFamily);
  if (handle == null) {
      throw new IOException("Voxy database lacks column family " + columnFamily);
  }
  return (byte[]) get.invoke(db.db, handle, key);
        }

        void forEach(Database db, String columnFamily, ByteConsumer consumer) throws Exception {
            Object handle = db.handlesByName.get(columnFamily);
            if (handle == null) {
                throw new IOException("Voxy database lacks column family " + columnFamily);
            }
            Object iterator = newIterator.invoke(db.db, handle);
            try {
                iteratorSeekToFirst.invoke(iterator);
                while ((boolean) iteratorIsValid.invoke(iterator)) {
                    consumer.accept((byte[]) iteratorKey.invoke(iterator), (byte[]) iteratorValue.invoke(iterator));
                    iteratorNext.invoke(iterator);
                }
            } finally {
                ((AutoCloseable) iterator).close();
            }
        }

        byte[] decompress(byte[] compressed) throws Exception {
            long declared = (long) zstdSize.invoke(zstd, compressed, 0, compressed.length);
            if (declared > MAX_VOXY_RAW_BYTES) {
                throw new IOException("Voxy Zstd frame expands to " + declared + " bytes");
            }
            int capacity = declared > 0 ? (int) declared : MAX_VOXY_RAW_BYTES;
            byte[] output = new byte[capacity];
            int actual = (int) zstdDecompress.invoke(zstd,
                    compressed, 0, compressed.length, output, 0, output.length);
            if (actual < 0 || actual > output.length) {
                throw new IOException("Invalid Voxy Zstd output length " + actual);
            }
            return actual == output.length ? output : java.util.Arrays.copyOf(output, actual);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }

        private static final class Database implements AutoCloseable {
            final AutoCloseable db;
            final AutoCloseable dbOptions;
            final List<AutoCloseable> cfOptions;
            final List<Object> handles;
            final Map<String, Object> handlesByName;

            Database(AutoCloseable db, AutoCloseable dbOptions, List<AutoCloseable> cfOptions,
                     List<Object> handles, Map<String, Object> handlesByName) {
                this.db = db;
                this.dbOptions = dbOptions;
                this.cfOptions = cfOptions;
                this.handles = handles;
                this.handlesByName = handlesByName;
            }

            @Override
            public void close() throws Exception {
                Exception failure = null;
                for (Object handle : handles) {
                    try {
                        ((AutoCloseable) handle).close();
                    } catch (Exception e) {
                        failure = merge(failure, e);
                    }
                }
                try {
                    db.close();
                } catch (Exception e) {
                    failure = merge(failure, e);
                }
                for (AutoCloseable option : cfOptions) {
                    try {
                        option.close();
                    } catch (Exception e) {
                        failure = merge(failure, e);
                    }
                }
                try {
                    dbOptions.close();
                } catch (Exception e) {
                    failure = merge(failure, e);
                }
                if (failure != null) {
                    throw failure;
                }
            }

            private static Exception merge(Exception current, Exception next) {
                if (current == null) {
                    return next;
                }
                current.addSuppressed(next);
                return current;
            }
        }
    }

    @FunctionalInterface
    private interface KeyConsumer {
        void accept(byte[] key) throws Exception;
    }

    @FunctionalInterface
    private interface ByteConsumer {
        void accept(byte[] key, byte[] value) throws Exception;
    }
}
