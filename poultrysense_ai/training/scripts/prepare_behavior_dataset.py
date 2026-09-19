#!/usr/bin/env python3
"""
Prepare a behavior/posture health classification dataset (NOT fecal imagery).

Target YOLO-classify layout:
  datasets/behavior_cls/
    train/{normal,lethargic,abnormal_posture}/*.jpg
    val/{normal,lethargic,abnormal_posture}/*.jpg

Sources:
  1) --from-captures  (recommended) crops exported by capture_behavior_crops.py
  2) manual folders you fill with chicken images

This does NOT download fecal/droppings datasets.
"""

from __future__ import annotations

import argparse
import random
import shutil
from pathlib import Path


CLASSES = ("normal", "lethargic", "abnormal_posture")


def split_dir(src_root: Path, out_root: Path, val_ratio: float, seed: int):
    random.seed(seed)
    if out_root.exists():
        shutil.rmtree(out_root)

    counts = {"train": {}, "val": {}}
    for cls in CLASSES:
        src = src_root / cls
        images = []
        if src.exists():
            images = [
                p
                for p in src.rglob("*")
                if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}
            ]
        random.shuffle(images)
        n_val = max(1, int(len(images) * val_ratio)) if len(images) > 1 else (1 if images else 0)
        val_imgs = images[:n_val]
        train_imgs = images[n_val:]

        for split, subset in (("train", train_imgs), ("val", val_imgs)):
            dest = out_root / split / cls
            dest.mkdir(parents=True, exist_ok=True)
            for i, img in enumerate(subset):
                shutil.copy2(img, dest / f"{cls}_{i:05d}{img.suffix.lower()}")
            counts[split][cls] = len(subset)

    return counts


def init_empty(captures_root: Path):
    for cls in CLASSES:
        (captures_root / cls).mkdir(parents=True, exist_ok=True)
    readme = captures_root / "README.txt"
    readme.write_text(
        "\n".join(
            [
                "PoultrySense behavior/posture crop buckets",
                "",
                "normal/              upright, walking, pecking, active birds",
                "lethargic/           idle / low-motion birds (possible illness)",
                "abnormal_posture/    lying, sprawled, collapsed posture",
                "",
                "Add chicken crop images (jpg/png) into these folders, then run:",
                "  python scripts/prepare_behavior_dataset.py --from-captures",
                "  python scripts/train_behavior_classifier.py",
                "",
            ]
        )
    )


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Prepare behavior/posture classifier dataset")
    parser.add_argument(
        "--captures",
        default=str(root / "datasets" / "behavior_captures"),
        help="Folder with normal|lethargic|abnormal_posture image buckets",
    )
    parser.add_argument(
        "--out",
        default=str(root / "datasets" / "behavior_cls"),
        help="YOLO-classify output root",
    )
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--init",
        action="store_true",
        help="Create empty capture folders + README and exit",
    )
    parser.add_argument(
        "--from-captures",
        action="store_true",
        help="Build train/val splits from --captures",
    )
    args = parser.parse_args()

    captures = Path(args.captures)
    out = Path(args.out)

    if args.init or not args.from_captures:
        init_empty(captures)
        if args.init or not args.from_captures:
            # Always ensure buckets exist
            print(f"[INFO] Capture buckets ready at: {captures}")
            for cls in CLASSES:
                n = len(list((captures / cls).glob("*"))) if (captures / cls).exists() else 0
                print(f"  {cls}: {n} files")
            if not args.from_captures:
                print(
                    "[NEXT] Add chicken crop photos into those folders, then re-run with --from-captures"
                )
                if not args.init:
                    # If user ran with no flags, still init and stop (no fecal download)
                    return

    if args.from_captures:
        total = 0
        for cls in CLASSES:
            bucket = captures / cls
            n = len(list(bucket.glob("*"))) if bucket.exists() else 0
            total += n
        if total == 0:
            raise SystemExit(
                f"No images in {captures}. Capture/live-label crops first "
                f"(python scripts/capture_behavior_crops.py) or copy photos manually."
            )
        counts = split_dir(captures, out, args.val_ratio, args.seed)
        print("[DONE] Behavior/posture dataset ready:")
        print(f"  path: {out}")
        print(f"  train: {counts['train']}")
        print(f"  val:   {counts['val']}")
        print("Next: python scripts/train_behavior_classifier.py")


if __name__ == "__main__":
    main()
