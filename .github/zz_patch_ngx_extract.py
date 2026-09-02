from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/ngx/NgxRuntime.java')
s = p.read_text()
old = '''        } catch (Throwable t) {
            Files.deleteIfExists(tmp);
            throw t;
        }
'''
new = '''        } catch (IOException | RuntimeException | Error t) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                t.addSuppressed(cleanup);
            }
            throw t;
        }
'''
assert old in s
p.write_text(s.replace(old, new, 1))
