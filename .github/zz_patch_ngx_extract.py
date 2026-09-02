from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()
s = s.replace('import java.nio.file.Path;\n', 'import java.nio.file.Path;\nimport java.nio.file.StandardCopyOption;\n', 1)
s = s.replace('import java.util.Arrays;\n', '', 1)

old = '''    private static boolean extractBundledNative(String name, Path dst) throws IOException {
        String resource = PLATFORM_NATIVES.resourceDir() + name;
        try (InputStream in = NgxRuntime.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            byte[] bytes = in.readAllBytes();
            if (!sameBytes(dst, bytes)) {
                Files.write(dst, bytes);
            }
            return true;
        }
    }
'''
new = '''    private static boolean extractBundledNative(String name, Path dst) throws IOException {
        String resource = PLATFORM_NATIVES.resourceDir() + name;
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try (InputStream in = NgxRuntime.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            // Stream large vendor runtimes straight to disk. Do not materialize a second 50-165 MB heap
            // array just to compare/update an already-extracted DLL.
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Files.deleteIfExists(tmp);
            throw t;
        }
        if (sameFileContents(dst, tmp)) {
            Files.deleteIfExists(tmp);
            return true;
        }
        try {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
'''
assert old in s
s = s.replace(old, new, 1)

old = '''    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
        try {
            return Files.size(path) == bytes.length && Arrays.equals(Files.readAllBytes(path), bytes);
        } catch (NoSuchFileException e) {
            return false;
        }
    }
'''
new = '''    private static boolean sameFileContents(Path a, Path b) throws IOException {
        try {
            if (Files.size(a) != Files.size(b)) {
                return false;
            }
        } catch (NoSuchFileException e) {
            return false;
        }
        try (InputStream left = Files.newInputStream(a); InputStream right = Files.newInputStream(b)) {
            byte[] lb = new byte[64 * 1024];
            byte[] rb = new byte[64 * 1024];
            while (true) {
                int ln = left.readNBytes(lb, 0, lb.length);
                int rn = right.readNBytes(rb, 0, rb.length);
                if (ln != rn) {
                    return false;
                }
                if (ln == 0) {
                    return true;
                }
                for (int i = 0; i < ln; i++) {
                    if (lb[i] != rb[i]) {
                        return false;
                    }
                }
            }
        }
    }
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
