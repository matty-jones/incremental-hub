#!/usr/bin/env python3
"""Instantiate static Manrope weights from the vendored variable font.

The source file and its checksum live in third_party/fonts/manrope/SOURCE.md.
Outputs app/src/main/res/font/manrope_{regular,medium,semibold,bold}.ttf.
"""

from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "third_party" / "fonts" / "manrope" / "Manrope[wght].ttf"
OUTPUT = ROOT / "app" / "src" / "main" / "res" / "font"

INSTANCES = (
    (400, "manrope_regular.ttf", "Regular"),
    (500, "manrope_medium.ttf", "Medium"),
    (600, "manrope_semibold.ttf", "SemiBold"),
    (700, "manrope_bold.ttf", "Bold"),
)


def set_subfamily(font: TTFont, subfamily: str) -> None:
    name = font["name"]
    for name_id in (2, 17):
        name.setName(subfamily, name_id, 3, 1, 0x409)
        name.setName(subfamily, name_id, 1, 0, 0)


def instantiate(weight: int, filename: str, subfamily: str) -> None:
    font = TTFont(SOURCE)
    instance = instantiateVariableFont(
        font,
        {"wght": weight},
        inplace=False,
        static=True,
        updateFontNames=False,
    )
    instance["OS/2"].usWeightClass = weight
    set_subfamily(instance, subfamily)
    destination = OUTPUT / filename
    instance.save(destination)

    saved = TTFont(destination)
    actual = saved["OS/2"].usWeightClass
    if actual != weight:
        raise SystemExit(f"{filename} usWeightClass {actual} != {weight}")
    if "fvar" in saved:
        raise SystemExit(f"{filename} is still a variable font")
    print(f"{filename} usWeightClass={actual}")


def main() -> None:
    if not SOURCE.is_file():
        raise SystemExit(f"missing {SOURCE}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for weight, filename, subfamily in INSTANCES:
        instantiate(weight, filename, subfamily)


if __name__ == "__main__":
    main()
