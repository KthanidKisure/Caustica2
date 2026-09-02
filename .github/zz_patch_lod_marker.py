from pathlib import Path

root = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain')
imp = root / 'RtCausticaLodImporter.java'
s = imp.read_text()

old = '''        Path marker = sessionRoot.resolve("wynnlod-v2.complete");
        if (Files.isRegularFile(marker)) {
            return;
        }
'''
new = '''        Path marker = sessionRoot.resolve("wynnlod-v2.complete");
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
'''
assert old in s
s = s.replace(old, new, 1)

old = '''            Files.writeString(marker,
                    "source=wynnlod-2.2.0\\nsha256=" + WYNNLOD_SHA256
                            + "\\nsections=" + stats.sections + "\\ntiles=" + stats.tiles + "\\nregions=" + stats.regions + "\\n",
                    StandardCharsets.UTF_8);
            RtCausticaLodPackedSource.markImportedPackReady(sessionRoot);
'''
new = '''            writeCompleteMarker(marker, stats);
            RtCausticaLodPackedSource.markImportedPackReady(sessionRoot);
'''
assert old in s
s = s.replace(old, new, 1)

anchor = '''    private static void importWynncraft(Path sessionRoot, Path marker) throws Exception {
'''
helpers = '''    /** True only for a fully written marker describing exactly the importer format/source we understand. */
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
        String contents = "source=wynnlod-2.2.0\\nsha256=" + WYNNLOD_SHA256
                + "\\nsections=" + stats.sections + "\\ntiles=" + stats.tiles + "\\nregions=" + stats.regions + "\\n";
        Path tmp = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.writeString(tmp, contents, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

'''
assert anchor in s
s = s.replace(anchor, helpers + anchor, 1)
imp.write_text(s)

packed = root / 'RtCausticaLodPackedSource.java'
p = packed.read_text()
old = '''        packReady = Files.isRegularFile(root.resolve("wynnlod-v2.complete"));
'''
new = '''        packReady = RtCausticaLodImporter.completedPackMarkerValid(root);
'''
assert old in p
p = p.replace(old, new, 1)
packed.write_text(p)
