from pathlib import Path

# --- NgxRuntime -------------------------------------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()

s = s.replace('import java.nio.file.Path;\nimport java.util.ArrayList;\n',
              'import java.nio.file.Path;\nimport java.security.MessageDigest;\nimport java.security.NoSuchAlgorithmException;\nimport java.util.ArrayList;\nimport java.util.HexFormat;\n', 1)

old = '''        } catch (Throwable t) {
            failed = true;
            lib = null;
            CausticaMod.LOGGER.error("NGX init failed; DLSS features disabled", t);
            return null;
        }
'''
new = '''        } catch (Throwable t) {
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
'''
assert old in s
s = s.replace(old, new, 1)

old = '''            if (!missingFeatures.isEmpty()) {
                CausticaMod.LOGGER.warn("NGX feature libraries {} not found next to {}; those features will be unavailable",
                        missingFeatures, PLATFORM_NATIVES.shimName());
            }
        }

        lib = NgxLibrary.load(shim);
'''
new = '''            if (!missingFeatures.isEmpty()) {
                CausticaMod.LOGGER.warn("NGX feature libraries {} not found next to {}; those features will be unavailable",
                        missingFeatures, PLATFORM_NATIVES.shimName());
            }
            logNativeInventory(nativesDir);
        }

        lib = NgxLibrary.load(shim);
'''
assert old in s
s = s.replace(old, new, 1)

anchor = '''    private static boolean sameBytes(Path path, byte[] bytes) throws IOException {
'''
helpers = '''    /**
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

'''
assert anchor in s
s = s.replace(anchor, helpers + anchor, 1)

old = '''    private record PlatformNatives(String platformDir, String shimName, List<String> exactFeatureNames,
                                   List<String> featureNamePrefixes, boolean supported) {
'''
new = '''    private record PlatformNatives(String platformDir, String shimName, List<String> exactFeatureNames,
                                   List<String> optionalFeatureNames, List<String> featureNamePrefixes,
                                   boolean supported) {
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                return new PlatformNatives("windows-x64", "ngxshim.dll",
                        List.of("nvngx_dlssd.dll", "nvngx_dlssg.dll"), List.of(), true);
'''
new = '''                return new PlatformNatives("windows-x64", "ngxshim.dll",
                        List.of("nvngx_dlssd.dll", "nvngx_dlssg.dll"),
                        // Streamline 2.13-era packages can additionally carry ordinary DLSS SR and
                        // DLSS-NR. They are recognized/extracted/fingerprinted when bundled, but are not
                        // required by Caustica's current RR/FG path and never cause a missing-file warning.
                        List.of("nvngx_dlss.dll", "nvngx_dlssnr.dll"), List.of(), true);
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                return new PlatformNatives("linux-x64", "libngxshim.so", List.of(),
                        List.of("libnvidia-ngx-dlssd.so", "libnvidia-ngx-dlssg.so"), true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("ngxshim"), List.of(), List.of(), false);
'''
new = '''                return new PlatformNatives("linux-x64", "libngxshim.so", List.of(), List.of(),
                        List.of("libnvidia-ngx-dlssd.so", "libnvidia-ngx-dlssg.so"), true);
            }
            return new PlatformNatives(os + "/" + arch, System.mapLibraryName("ngxshim"),
                    List.of(), List.of(), List.of(), false);
'''
assert old in s
s = s.replace(old, new, 1)

old = '''        private boolean isFeatureLibrary(String name) {
            return exactFeatureNames.contains(name)
                    || featureNamePrefixes.stream().anyMatch(name::startsWith);
        }
'''
new = '''        private boolean isFeatureLibrary(String name) {
            return exactFeatureNames.contains(name) || optionalFeatureNames.contains(name)
                    || featureNamePrefixes.stream().anyMatch(name::startsWith);
        }
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

# --- RR reset ---------------------------------------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java')
s = p.read_text()
old = '''        initialized = false;
        lib = null;
    }
'''
new = '''        // A feature failure is session/device-local. A full RT teardown must permit a clean retry on
        // the next Vulkan device instead of leaving RR permanently latched off for this JVM.
        initialized = false;
        failed = false;
        loggedAvailable = false;
        resetHistory = false;
        lastFrameNanos = 0L;
        lib = null;
    }
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

# --- FG reset ---------------------------------------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java')
s = p.read_text()
old = '''        initialized = false;
        lib = null;
    }
'''
new = '''        // Availability/failure state belongs to this NGX/device lifetime. Reset it with the feature so
        // disabling/re-enabling RT or recreating the Vulkan device can probe a newer/recovered runtime.
        initialized = false;
        failed = false;
        probed = false;
        available = false;
        multiFrameCountMax = 0;
        lib = null;
    }
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
