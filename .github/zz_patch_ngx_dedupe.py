from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()
old = '''    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
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
'''
new = '''    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        java.util.HashSet<String> current = new java.util.HashSet<>();
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            if (extractBundledNative(name, dir.resolve(name))) {
                current.add(name);
            }
        }
        for (String name : bundledFeatureLibraryNames()) {
            // Required RR/FG names are also visible to the generic resource scan. Avoid streaming and
            // byte-comparing those large DLLs twice on every startup.
            if (current.contains(name)) {
                continue;
            }
            if (extractBundledNative(name, dir.resolve(name))) {
                current.add(name);
            }
        }
'''
assert old in s
p.write_text(s.replace(old, new, 1))
