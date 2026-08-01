#!/usr/bin/env python3
"""Inspect a Caustica F2 EXR and print its exposure/recovery metadata."""

from __future__ import annotations

import argparse
from pathlib import Path

import OpenEXR


CAUSTICA_KEYS = (
    "causticaColorSpace",
    "causticaEncoding",
    "causticaRecovery",
    "causticaPreExposure",
    "causticaResidualExposure",
    "causticaAbsoluteExposure",
    "causticaExposureMode",
    "causticaEvScene",
    "causticaEvTarget",
    "causticaEvApplied",
    "causticaLookIntent",
    "causticaFrame",
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Print dimensions, channels, and Caustica metadata from an OpenEXR screenshot."
    )
    parser.add_argument("image", type=Path)
    args = parser.parse_args()

    with OpenEXR.File(str(args.image), separate_channels=True, header_only=True) as image:
        header = image.header()
        window = header["dataWindow"]
        width = int(window[1][0] - window[0][0] + 1)
        height = int(window[1][1] - window[0][1] + 1)
        print(f"{args.image}: {width}x{height}")
        print("channels:", ", ".join(channel.name for channel in header["channels"]))
        for key in CAUSTICA_KEYS:
            if key in header:
                print(f"{key}: {header[key]}")


if __name__ == "__main__":
    main()
