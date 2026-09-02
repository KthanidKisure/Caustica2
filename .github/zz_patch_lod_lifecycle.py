from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java')
s = p.read_text()
needle = 'import dev.comfyfluffy.caustica.rt.terrain.RtCausticaLodSource;\n'
assert needle in s
s = s.replace(needle, needle + 'import dev.comfyfluffy.caustica.rt.terrain.RtDhLodSource;\n', 1)
# Keep tick() on the live source; replace only the two lifecycle invalidations.
assert s.count('RtCausticaLodSource.invalidate();') == 2
s = s.replace('RtCausticaLodSource.invalidate();', 'RtDhLodSource.invalidate();')
p.write_text(s)
