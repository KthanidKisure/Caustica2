package dev.comfyfluffy.caustica.ngx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;

import net.fabricmc.loader.api.FabricLoader;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared NVIDIA NGX lifetime for the mod. Loads the native shim, extracts the bundled NGX feature DLLs,
 * and runs {@code ngxshim_init} / {@code ngxshim_shutdown} exactly once per Vulkan device. Multiple NGX
 * features (DLSS Ray Reconstruction, and later Frame Generation) share this single initialized
 * {@link NgxLibrary}; each feature owns only its own create/evaluate/release. NGX is shut down only at
 * device teardown (so releasing one feature can't tear NGX down while another still holds a handle).
 */
public final class NgxRuntime {
    public static final NgxRuntime INSTANCE = new NgxRuntime();

    private static final PlatformNatives PLATFORM_NATIVES = PlatformNatives.current();

    private NgxLibrary lib;
    private boolean initialized;
    private boolean failed;

    private NgxRuntime() {
    }

    /**
     * Ensure NGX is loaded and initialized for {@code device}, returning the shared {@link NgxLibrary}, or
     * {@code null} if it is unavailable. Idempotent; latches failure so it isn't retried every frame
     * (cleared by {@link #shutdown()} so a fresh device can re-init).
     */
    public synchronized NgxLibrary acquire(VulkanDevice device) {
        if (initialized) {
            return lib;
        }
        if (failed) {
            return null;
        }
        try {
            init(device);
            initialized = true;
            return lib;
        } catch (Throwable t) {
            // NGX can fail after loading the shim and partially initializing device-global state.
            // Best-effort shutdown keeps a later RT/device restart from inheriting that half-state.
            if (lib != null) {
                try {
                    lib.shutdown(device.vkDevice().address());
                } catch (Throwable cleanup) {
                    if (cleanup != t) {
                        t.addSuppressed(cleanup);
                    }
                }
            }
            initialized = false;
            failed = true;
            lib = null;
            CausticaMod.LOGGER.error("NGX init failed; DLSS features disabled", t);
            return null;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /** The shared library once {@link #acquire} has succeeded, else {@code null}. */
    public NgxLibrary library() {
        return lib;
    }

    /**
     * Shut down NGX. Call only at device teardown, after every feature has been released. Resolves the
     * device from the current render backend; no-op if NGX was never initialized.
     */
    public synchronized void shutdown() {
        if (lib != null && initialized
                && ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            try {
                lib.shutdown(device.vkDevice().address());
            } catch (Throwable t) {
                CausticaMod.LOGGER.warn("NGX shutdown failed", t);
            }
        }
        initialized = false;
        failed = false;
        lib = null;
    }

    /** NVSDK_NGX_Result: failure when the top 12 bits == 0xBAD. Shared by all NGX feature wrappers. */
    public static boolean ngxFailed(int result) {
        return (result & 0xFFF00000) == 0xBAD00000;
    }

    private void init(VulkanDevice device) {
        if (!PLATFORM_NATIVES.supported()) {
            throw new IllegalStateException("NGX natives are not bundled for " + PLATFORM_NATIVES.platformDir());
        }
        Path shim = locateShim();
        if (shim == null) {
            throw new IllegalStateException(PLATFORM_NATIVES.shimName()
                    + " not found (bundled natives or -Dcaustica.ngx.path)");
        }
        Path nativesDir = shim.getParent();
        if (nativesDir != null) {
            List<String> missingFeatures = missingFeatureLibraries(nativesDir);
            if (!missingFeatures.isEmpty()) {
                CausticaMod.LOGGER.warn("NGX feature libraries {} not found next to {}; those features will be unavailable",
                        missingFeatures, PLATFORM_NATIVES.shimName());
            }
            logNativeInventory(nativesDir);
        }

        lib = NgxLibrary.load(shim);

        Path dataPath = FabricLoader.getInstance().getGameDir().resolve("caustica-ngx");
        try {
            Files.createDirectories(dataPath);
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Could not create NGX data path {}", dataPath, e);
        }

        VkInstance instance = device.vkDevice().getPhysicalDevice().getInstance();
        try (Arena arena = Arena.ofConfined()) {
            long gdpa;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                gdpa = VK10.vkGetInstanceProcAddr(instance, stack.ASCII("vkGetDeviceProcAddr"));
            }
            int rc = lib.init(0L, wideString(arena, dataPath.toString()),
                    instance.address(), device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(),
                    0L, gdpa, wideString(arena, nativesDir == null ? "" : nativesDir.toString()));
            if (ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_init failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
        }
        CausticaMod.LOGGER.info("NGX initialized (shim {})", shim);
    }

    private static Path locateShim() {
        String override = CausticaConfig.Ngx.PATH.get();
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isDirectory(p)) {
                p = p.resolve(PLATFORM_NATIVES.shimName());
            }
            return Files.isRegularFile(p) ? p : null;
        }
        return extractBundledNatives();
    }

    private static Path extractBundledNatives() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-ngx")
                .resolve("natives").resolve(PLATFORM_NATIVES.platformDir());
        try {
            Files.createDirectories(dir);
            boolean hasShim = extractBundledNative(PLATFORM_NATIVES.shimName(), dir.resolve(PLATFORM_NATIVES.shimName()));
            extractBundledFeatureLibraries(dir);
            return hasShim && Files.isRegularFile(dir.resolve(PLATFORM_NATIVES.shimName()))
                    ? dir.resolve(PLATFORM_NATIVES.shimName()) : null;
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Could not extract bundled NGX natives to {}", dir, e);
            return null;
        }
    }

    private static boolean extractBundledNative(String name, Path dst) throws IOException {
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

    private static void extractBundledFeatureLibraries(Path dir) throws IOException {
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
        for (String name : bundledFeatureLibraryNames()) {
            extractBundledNative(name, dir.resolve(name));
        }
    }

    private static List<String> bundledFeatureLibraryNames() {
        List<String> names = new ArrayList<>();
        FabricLoader.getInstance().getModContainer("caustica").ifPresent(container -> {
            String nativeDir = "caustica/natives/" + PLATFORM_NATIVES.platformDir();
            for (Path root : container.getRootPaths()) {
                Path dir = root.resolve(nativeDir);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    files.map(path -> path.getFileName().toString())
                            .filter(PLATFORM_NATIVES::isFeatureLibrary)
                            .forEach(names::add);
                } catch (IOException e) {
                    CausticaMod.LOGGER.warn("Could not list bundled NGX natives in {}", dir, e);
                }
            }
        });
        return names;
    }

    private static List<String> missingFeatureLibraries(Path dir) {
        List<String> missing = new ArrayList<>();
        for (String name : PLATFORM_NATIVES.exactFeatureNames()) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                missing.add(name);
            }
        }
        List<String> names;
        try (Stream<Path> files = Files.list(dir)) {
            names = files.map(path -> path.getFileName().toString()).toList();
        } catch (IOException e) {
            return PLATFORM_NATIVES.featureDescriptions();
        }
        for (String prefix : PLATFORM_NATIVES.featureNamePrefixes()) {
            if (names.stream().noneMatch(name -> name.startsWith(prefix))) {
                missing.add(prefix + "*");
            }
        }
        return missing;
    }

    /**
     * One-time exact inventory of the shim and feature runtimes. This makes runtime logs self-identifying
     * when testing vendor DLL swaps (for example 310.7.x vs 310.8.x) without trusting file timestamps.
     */
    private static void logNativeInventory(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals(PLATFORM_NATIVES.shimName()) || PLATFORM_NATIVES.isFeatureLibrary(name);
                    })
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            CausticaMod.LOGGER.info("NGX native {}: {} bytes, sha256={}",
                                    path.getFileName(), Files.size(path), sha256(path));
                        } catch (IOException e) {
                            CausticaMod.LOGGER.debug("Could not fingerprint NGX native {}: {}", path, e.toString());
                        }
                    });
        } catch (IOException e) {
            CausticaMod.LOGGER.debug("Could not inventory NGX native directory {}: {}", dir, e.toString());
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean sameFileContents(Path a, Path b) throws IOException {
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

    // Native wchar_t width differs by platform: 2 bytes (UTF-16) on Windows, 4 bytes (UTF-32)
    // on Linux. Encode paths to the platform width expected by the NGX C ABI.
    private static final boolean WCHAR_IS_UTF16 =
            System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Charset WCHAR_CHARSET =
            WCHAR_IS_UTF16 ? StandardCharsets.UTF_16LE : Charset.forName("UTF-32LE");
    private static final int WCHAR_SIZE = WCHAR_IS_UTF16 ? 2 : 4;

    private static MemorySegment wideString(Arena arena, String s) {
        byte[] data = s.getBytes(WCHAR_CHARSET);
        MemorySegment seg = arena.allocate((long) data.length + WCHAR_SIZE);
        MemorySegment.copy(data, 0, seg, ValueLayout.JAVA_BYTE, 0, data.length);
        for (int i = 0; i < WCHAR_SIZE; i++) {
            seg.set(ValueLayout.JAVA_BYTE, data.length + i, (byte) 0);
        }
        return seg;
    }

    private record PlatformNatives(String platformDir, String shimName, List<String> exactFeatureNames,
                                   List<String> optionalFeatureNames, List<String> featureNamePrefixes,
                                   boolean supported) {
        private static PlatformNatives current() {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
            if (os.contains("win") && x64) {
                return new PlatformNatives("windows-x64", "ngxshim.dll",
                        List.of("nvngx_dlssd.dll", "nvngx_dlssg.dll"),
                        // Streamline 2.13-era packages can additionally carry ordinary DLSS SR and
                        // DLSS-NR. They are recognized/extracted/fingerprinted when bundled, but are not
                        // required by Caustica's current RR/FG path and never cause a missing-file warning.
                        List.of("nvngx_dlss.dll", "nvngx_dlssnr.dll"), List.of(), true);
            }
            if (os.contains("linux") && x64) {
                return new PlatformNatives("linux-x64", "libngxshim.so", List.of(), List.of(),
                        List.of("libnvidia-ngx-dlssd.so", "libnvidia-ngx-dlssg.so"), true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("ngxshim"),
                    List.of(), List.of(), List.of(), false);
        }

        private String resourceDir() {
            return "/caustica/natives/" + platformDir + "/";
        }

        private boolean isFeatureLibrary(String name) {
            return exactFeatureNames.contains(name) || optionalFeatureNames.contains(name)
                    || featureNamePrefixes.stream().anyMatch(name::startsWith);
        }

        private List<String> featureDescriptions() {
            List<String> descriptions = new ArrayList<>(exactFeatureNames);
            featureNamePrefixes.stream()
                    .map(prefix -> prefix + "*")
                    .forEach(descriptions::add);
            return descriptions;
        }
    }
}
