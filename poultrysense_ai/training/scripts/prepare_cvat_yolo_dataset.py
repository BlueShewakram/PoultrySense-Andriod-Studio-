#!/usr/bin/env python3
"""Prepare a CVAT-exported YOLO dataset for PoultrySense.

Example CVAT export layout:
  /downloads/cvat_export/
    train/
      images/
      labels/
    valid/
      images/
      labels/

This script copies it into the project layout expected by:
  training/scripts/train_chicken_detector.py

Expected output layout:
  training/datasets/chicken_det/
    data.yaml
    images/train/
    labels/train/
    images/val/
    labels/val/
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def copy_split(src_root: Path, dst_root: Path, split_name: str, src_rel: str) -> None:
    src_images = src_root / src_rel / "images"
    src_labels = src_root / src_rel / "labels"
    if not src_images.exists() or not src_labels.exists():
        raise FileNotFoundError(f"Missing expected folders in {src_root / src_rel}")

    dst_img = dst_root / "images" / split_name
    dst_lbl = dst_root / "labels" / split_name
    dst_img.mkdir(parents=True, exist_ok=True)
    dst_lbl.mkdir(parents=True, exist_ok=True)

    for image in sorted(src_images.iterdir()):
        if image.is_file():
            shutil.copy2(image, dst_img / image.name)

    for label in sorted(src_labels.iterdir()):
        if label.is_file():
            shutil.copy2(label, dst_lbl / label.name)

    print(f"[OK] {src_rel} -> {dst_img} + {dst_lbl}")


def main():
    parser = argparse.ArgumentParser(description="Convert a CVAT YOLO export into the PoultrySense project layout")
    parser.add_argument("--source", required=True, help="Path to the exported CVAT folder")
    parser.add_argument("--out", default="training/datasets/chicken_det", help="Project output root")
    args = parser.parse_args()

    src = Path(args.source).resolve()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)

    # Common CVAT export layout supports train/valid or train/val.
    for split in (("train", "train"), ("valid", "val"), ("val", "val")):
        src_rel = split[0]
        out_name = split[1]
        candidate = src / src_rel
        if not candidate.exists():
            continue
        copy_split(src, out, out_name, src_rel)

    (out / "data.yaml").write_text(
        "train: images/train\n"
        "val: images/val\n"
        "nc: 1\n"
        "names: ['chicken']\n",
        encoding="utf-8",
    )

    print(f"[DONE] Prepared dataset at: {out}")
    print("Next: ./venv/bin/python training/scripts/train_chicken_detector.py --data training/datasets/chicken_det --epochs 60")


if __name__ == "__main__":
    main()
