#!/usr/bin/env python3
"""Import a Roboflow YOLO export (zip or folder) and prepare it for PoultrySense training.

Usage:
  ./venv/bin/python import_roboflow_export.py --source /path/to/roboflow_export.zip --out ../datasets/chicken_det

The script will:
- unzip if necessary
- detect common Roboflow layouts (data.yaml, train/valid/images/labels or images/train/labels/train)
- copy files into the expected layout:
  out/
    data.yaml
    images/train
    images/val
    labels/train
    labels/val

It is tolerant of 'valid' vs 'val' naming.
"""
from __future__ import annotations

import argparse
import shutil
import tempfile
import zipfile
from pathlib import Path


def find_candidate_dirs(root: Path) -> dict:
    # Look for common layouts and return mapping
    mapping = {}
    # direct root with data.yaml
    if (root / 'data.yaml').exists():
        mapping['root'] = root
    # dataset/train/images or dataset/images/train
    for train_name in ('train', 'train/images'):
        if (root / train_name).exists():
            mapping['train_images'] = root / train_name
    for val_name in ('valid', 'val', 'valid/images', 'val/images'):
        if (root / val_name).exists():
            mapping['val_images'] = root / val_name
    # labels locations
    for lbl in ('train/labels', 'labels/train', 'valid/labels', 'labels/val'):
        p = root / lbl
        if p.exists():
            if 'train' in lbl and 'train_labels' not in mapping:
                mapping['train_labels'] = p
            if 'val' in lbl and 'val_labels' not in mapping:
                mapping['val_labels'] = p
    # fallback: images and labels at root/images etc
    if (root / 'images').exists():
        if (root / 'images' / 'train').exists():
            mapping['train_images'] = root / 'images' / 'train'
        if (root / 'images' / 'val').exists():
            mapping['val_images'] = root / 'images' / 'val'
    return mapping


def copy_split(src_images: Path, src_labels: Path, dst_images: Path, dst_labels: Path):
    dst_images.mkdir(parents=True, exist_ok=True)
    dst_labels.mkdir(parents=True, exist_ok=True)
    # copy images
    for f in sorted(src_images.iterdir()):
        if f.is_file():
            shutil.copy2(f, dst_images / f.name)
    # copy labels (matching txts)
    for f in sorted(src_labels.iterdir()):
        if f.is_file():
            shutil.copy2(f, dst_labels / f.name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, help='Path to Roboflow export (zip or folder)')
    parser.add_argument('--out', default='../datasets/chicken_det', help='Output dataset root (relative to this script)')
    args = parser.parse_args()

    src = Path(args.source).expanduser().resolve()
    out = (Path(__file__).resolve().parents[1] / args.out).resolve()

    workdir = None
    try:
        if src.is_file() and zipfile.is_zipfile(src):
            workdir = Path(tempfile.mkdtemp(prefix='rf_export_'))
            print(f'[INFO] Extracting zip to {workdir}')
            with zipfile.ZipFile(src, 'r') as z:
                z.extractall(workdir)
            rf_root = workdir
        elif src.is_dir():
            rf_root = src
        else:
            raise SystemExit(f'Unknown source: {src}')

        cand = find_candidate_dirs(rf_root)
        print(f'[DEBUG] Candidates: {cand.keys()}')

        out.mkdir(parents=True, exist_ok=True)

        # copy data.yaml if present
        data_yaml = None
        if (rf_root / 'data.yaml').exists():
            data_yaml = rf_root / 'data.yaml'
        else:
            # search for data.yaml in subfolders
            for p in rf_root.rglob('data.yaml'):
                data_yaml = p
                break
        if data_yaml:
            shutil.copy2(data_yaml, out / 'data.yaml')
            print(f'[OK] Copied data.yaml -> {out / "data.yaml"}')
        else:
            # write a minimal data.yaml
            (out / 'data.yaml').write_text('train: images/train\nval: images/val\nnc: 1\nnames: [\'chicken\']\n')
            print('[WARN] No data.yaml found; wrote minimal data.yaml')

        # Determine train images/labels
        # Common Roboflow layout: train/images, train/labels, valid/images, valid/labels
        train_images = None
        train_labels = None
        val_images = None
        val_labels = None

        # try several common patterns
        for candidate in (rf_root, ):
            for p in ('train/images', 'images/train', 'train', 'images'):
                if (candidate / p).exists() and any((candidate / p).glob('*.jpg')):
                    train_images = candidate / p
                    break
            for p in ('train/labels', 'labels/train', 'labels'):
                if (candidate / p).exists() and any((candidate / p).glob('*.txt')):
                    train_labels = candidate / p
                    break
            for p in ('valid/images', 'val/images', 'valid', 'val'):
                if (candidate / p).exists() and any((candidate / p).glob('*.jpg')):
                    val_images = candidate / p
                    break
            for p in ('valid/labels', 'labels/val'):
                if (candidate / p).exists() and any((candidate / p).glob('*.txt')):
                    val_labels = candidate / p
                    break

        # fallback: try subfolders discovery
        if train_images is None:
            for p in rf_root.rglob('images'):
                if any(p.glob('*.jpg')) and 'train' in str(p).lower():
                    train_images = p
                    break
        if train_labels is None:
            for p in rf_root.rglob('labels'):
                if any(p.glob('*.txt')) and 'train' in str(p).lower():
                    train_labels = p
                    break

        # If still missing, try to map images/labels by common folder names
        if train_images and train_labels:
            copy_split(train_images, train_labels, out / 'images' / 'train', out / 'labels' / 'train')
            print('[OK] Copied train split')
        else:
            print('[ERROR] Could not find both train images and train labels. Please inspect the export and try again.')
            return

        if val_images and val_labels:
            copy_split(val_images, val_labels, out / 'images' / 'val', out / 'labels' / 'val')
            print('[OK] Copied val split')
        else:
            print('[WARN] Validation split not found; copying 10% of train to val')
            imgs = sorted((out / 'images' / 'train').iterdir())
            n = max(1, len(imgs) // 10)
            for img in imgs[:n]:
                lbl = (out / 'labels' / 'train' / (img.stem + '.txt'))
                shutil.move(str(img), str(out / 'images' / 'val' / img.name))
                if lbl.exists():
                    shutil.move(str(lbl), str(out / 'labels' / 'val' / lbl.name))
            print(f'[OK] Moved {n} images to val split')

        print(f'[DONE] Dataset prepared at: {out}')
    finally:
        if workdir and workdir.exists():
            shutil.rmtree(workdir, ignore_errors=True)


if __name__ == '__main__':
    main()
