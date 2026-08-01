import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np

from tools import bake_display_lut as baker


class BakedLookResourceTest(unittest.TestCase):
    def test_default_package_lmt_has_expected_header_and_finite_payload(self):
        path = baker.LOOK_PACKAGE_DIR / "lmt.bin"
        data = path.read_bytes()
        magic, version, size, lo_stops, hi_stops = struct.unpack_from("<4sIIff", data)
        self.assertEqual(magic, b"CLUT", path)
        self.assertEqual(version, 1, path)
        self.assertEqual(size, 65, path)
        self.assertEqual(lo_stops, baker.SHAPER_LO_STOPS, path)
        self.assertEqual(hi_stops, baker.SHAPER_HI_STOPS, path)
        payload = np.frombuffer(data, dtype=np.float16, offset=20)
        self.assertEqual(payload.size, size ** 3 * 4, path)
        self.assertTrue(np.isfinite(payload).all(), path)
        self.assertGreaterEqual(float(payload.min()), 0.0, path)
        self.assertLessEqual(float(payload.max()), 1.0, path)
        rgba = payload.reshape(size, size, size, 4)
        axis = np.linspace(0.0, 1.0, size)
        b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
        identity = np.stack([r, g, b], axis=-1)
        self.assertGreater(float(np.max(np.abs(rgba[..., :3] - identity))), 0.005, path)


class CubeImportTest(unittest.TestCase):
    def test_reads_normalized_cube_in_r_fastest_order(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "curve.cube"
            rows = [
                "0 0 0", "1 0 0",
                "0 1 0", "1 1 0",
                "0 0 1", "1 0 1",
                "0 1 1", "1 1 1",
            ]
            path.write_text(
                'TITLE "Test Curve"\nLUT_3D_SIZE 2\n' + "\n".join(rows) + "\n",
                encoding="utf-8",
            )

            size, rgb, title = baker.read_shaper_cube(path)

            self.assertEqual(size, 2)
            self.assertEqual(title, "Test Curve")
            np.testing.assert_array_equal(rgb[0, 0, 1], np.array([1, 0, 0], dtype=np.float32))
            np.testing.assert_array_equal(rgb[1, 1, 0], np.array([0, 1, 1], dtype=np.float32))

    def test_rejects_non_normalized_domain(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "linear.cube"
            path.write_text(
                "LUT_3D_SIZE 2\nDOMAIN_MAX 16 16 16\n" + ("0 0 0\n" * 8),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "must use DOMAIN_MIN"):
                baker.read_shaper_cube(path)


if __name__ == "__main__":
    unittest.main()
