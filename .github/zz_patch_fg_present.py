from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/rt/RtFramePresenter.java')
s = p.read_text()
old = '''            beforeBlit.get(1).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L)
'''
new = '''            beforeBlit.get(1).sType$Default().srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L)
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('import org.lwjgl.vulkan.VkMemoryBarrier2;\n', '', 1)
p.write_text(s)
