from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()

s = s.replace('import java.security.MessageDigest;\n', 'import java.security.DigestInputStream;\nimport java.security.MessageDigest;\n', 1)
s = s.replace('import java.util.ArrayList;\n', 'import java.util.ArrayList;\nimport java.util.HashMap;\n', 1)
s = s.replace('import java.util.List;\n', 'import java.util.List;\nimport java.util.Map;\n', 1)

old = '''    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();\n\n    private NgxLibrary lib;\n'''
new = '''    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();\n    // Filled while verifying/extracting bundled natives, then reused by the inventory logger so large\n    // feature DLLs are not read from disk yet another time just to print their SHA-256. acquire() is\n    // synchronized, so this map is only mutated on the serialized NGX initialization path.\n    private static final Map<String, NativeFingerprint> BUNDLED_FINGERPRINTS = new HashMap<>();\n\n    private NgxLibrary lib;\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''        try {\n            Files.createDirectories(dir);\n            boolean hasShim = extractBundledNative(PLATFORM_NATIVES.shimName(), dir.resolve(PLATFORM_NATIVES.shimName()));\n'''
new = '''        try {\n            Files.createDirectories(dir);\n            BUNDLED_FINGERPRINTS.clear();\n            boolean hasShim = extractBundledNative(PLATFORM_NATIVES.shimName(), dir.resolve(PLATFORM_NATIVES.shimName()));\n'''
assert old in s
s = s.replace(old, new, 1)

start = s.index('    private static boolean extractBundledNative(String name, Path dst) throws IOException {')
end = s.index('    private static void extractBundledFeatureLibraries(Path dir) throws IOException {', start)
replacement = '''    private static boolean extractBundledNative(String name, Path dst) throws IOException {\n        String resource = PLATFORM_NATIVES.resourceDir() + name;\n\n        // Common launch path: compare the bundled resource directly against the previously extracted file.\n        // No temporary 50-165 MB write is performed when the DLL is unchanged. The digest is computed from\n        // the bundled bytes during the same pass and reused by logNativeInventory().\n        if (Files.isRegularFile(dst)) {\n            try (InputStream raw = NgxRuntime.class.getResourceAsStream(resource)) {\n                if (raw == null) {\n                    return false;\n                }\n                MessageDigest digest = newSha256();\n                try (DigestInputStream bundled = new DigestInputStream(raw, digest);\n                        InputStream existing = Files.newInputStream(dst)) {\n                    long size = compareStreams(bundled, existing);\n                    if (size >= 0L) {\n                        BUNDLED_FINGERPRINTS.put(name,\n                                new NativeFingerprint(size, HexFormat.of().formatHex(digest.digest())));\n                        return true;\n                    }\n                }\n            }\n        }\n\n        // First install or changed runtime: stream to a sibling temp file, hash while copying, then publish\n        // atomically. This keeps partial/crashed updates from replacing the last known-good native.\n        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");\n        Files.deleteIfExists(tmp);\n        MessageDigest digest = newSha256();\n        try (InputStream raw = NgxRuntime.class.getResourceAsStream(resource)) {\n            if (raw == null) {\n                return false;\n            }\n            try (DigestInputStream in = new DigestInputStream(raw, digest)) {\n                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);\n            }\n        } catch (IOException | RuntimeException | Error t) {\n            try {\n                Files.deleteIfExists(tmp);\n            } catch (IOException cleanup) {\n                t.addSuppressed(cleanup);\n            }\n            throw t;\n        }\n        long size = Files.size(tmp);\n        BUNDLED_FINGERPRINTS.put(name, new NativeFingerprint(size, HexFormat.of().formatHex(digest.digest())));\n        try {\n            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);\n        } catch (IOException atomicUnsupported) {\n            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);\n        }\n        return true;\n    }\n\n    /**\n     * Compare two streams without retaining the file in heap. Returns the number of identical bundled bytes\n     * when both streams reach EOF together, or -1 at the first mismatch.\n     */\n    private static long compareStreams(InputStream bundled, InputStream existing) throws IOException {\n        byte[] left = new byte[64 * 1024];\n        byte[] right = new byte[64 * 1024];\n        long total = 0L;\n        while (true) {\n            int ln = bundled.readNBytes(left, 0, left.length);\n            int rn = existing.readNBytes(right, 0, right.length);\n            if (ln != rn) {\n                return -1L;\n            }\n            if (ln == 0) {\n                return total;\n            }\n            for (int i = 0; i < ln; i++) {\n                if (left[i] != right[i]) {\n                    return -1L;\n                }\n            }\n            total += ln;\n        }\n    }\n\n'''
s = s[:start] + replacement + s[end:]

old = '''                        try {\n                            CausticaMod.LOGGER.info("NGX native {}: {} bytes, sha256={}",\n                                    path.getFileName(), Files.size(path), sha256(path));\n                        } catch (IOException e) {\n'''
new = '''                        try {\n                            String name = path.getFileName().toString();\n                            long size = Files.size(path);\n                            NativeFingerprint bundled = BUNDLED_FINGERPRINTS.get(name);\n                            String digest = bundled != null && bundled.size() == size\n                                    ? bundled.sha256() : sha256(path);\n                            CausticaMod.LOGGER.info("NGX native {}: {} bytes, sha256={}",\n                                    path.getFileName(), size, digest);\n                        } catch (IOException e) {\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''    private static String sha256(Path path) throws IOException {\n        final MessageDigest digest;\n        try {\n            digest = MessageDigest.getInstance("SHA-256");\n        } catch (NoSuchAlgorithmException impossible) {\n            throw new IllegalStateException("SHA-256 unavailable", impossible);\n        }\n'''
new = '''    private static MessageDigest newSha256() {\n        try {\n            return MessageDigest.getInstance("SHA-256");\n        } catch (NoSuchAlgorithmException impossible) {\n            throw new IllegalStateException("SHA-256 unavailable", impossible);\n        }\n    }\n\n    private static String sha256(Path path) throws IOException {\n        MessageDigest digest = newSha256();\n'''
assert old in s
s = s.replace(old, new, 1)

# old file-vs-file compare is no longer used after the read-only bundled-vs-existing fast path.
start = s.index('    private static boolean sameFileContents(Path a, Path b) throws IOException {')
end = s.index('    // Native wchar_t width differs by platform:', start)
s = s[:start] + '    private record NativeFingerprint(long size, String sha256) {\n    }\n\n' + s[end:]

# NoSuchFileException was only used by sameFileContents().
s = s.replace('import java.nio.file.NoSuchFileException;\n', '')

p.write_text(s)
