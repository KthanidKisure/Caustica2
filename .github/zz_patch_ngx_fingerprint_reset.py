from pathlib import Path
p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()
old = '''    private void init(VulkanDevice device) {\n        if (!PLATFORM_NATIVES.supported()) {\n'''
new = '''    private void init(VulkanDevice device) {\n        // Never carry bundled-file fingerprints across NGX lifetimes or into an explicit external native\n        // override. External runtimes are hashed from the files that will actually be loaded.\n        BUNDLED_FINGERPRINTS.clear();\n        if (!PLATFORM_NATIVES.supported()) {\n'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('            BUNDLED_FINGERPRINTS.clear();\n            boolean hasShim = extractBundledNative(',
              '            boolean hasShim = extractBundledNative(', 1)
p.write_text(s)
