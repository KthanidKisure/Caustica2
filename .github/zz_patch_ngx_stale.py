from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()
old = '''    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
        for (String name : bundledFeatureLibraryNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
    }
'''
new = '''    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        java.util.HashSet<String> current = new java.util.HashSet<>();
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            if (extractBundledNative(name, dir.resolve(name))) {
                current.add(name);
            }
        }
        for (String name : bundledFeatureLibraryNames()) {
            if (extractBundledNative(name, dir.resolve(name))) {
                current.add(name);
            }
        }

        // This directory is Caustica-managed. Remove recognized feature runtimes left behind by an older
        // JAR so switching 310.x generations cannot accidentally load a stale FG/RR/NR/SR binary.
        List<Path> stale;
        try (Stream<Path> files = Files.list(dir)) {
            stale = files.filter(Files::isRegularFile)
                    .filter(path -> PLATFORM_NATIVES.isFeatureLibrary(path.getFileName().toString()))
                    .filter(path -> !current.contains(path.getFileName().toString()))
                    .toList();
        }
        for (Path path : stale) {
            Files.deleteIfExists(path);
            CausticaMod.LOGGER.info("Removed stale NGX feature runtime {}", path.getFileName());
        }
    }
'''
assert old in s
p.write_text(s.replace(old, new, 1))
