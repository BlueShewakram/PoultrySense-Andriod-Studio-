"""Quick FP32-vs-INT8 sanity benchmark on the bundled chicken val split.

Not an accuracy report: it only shows whether INT8 still finds the same birds
and how fast it runs on this machine.

Usage (from poultrysense_ai):
    venv\\Scripts\\python.exe bench_int8.py [fp32_dir] [int8_dir]
"""

import glob
import os
import sys
import time

from ultralytics import YOLO

BASE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(BASE, "models")
INT8 = os.path.join(MODELS, "PoultrySense_ChickenDet_v5_26n_openvino_model")
IMAGES = sorted(
    glob.glob(os.path.join(BASE, "datasets", "chicken_det", "valid", "images", "*.jpg"))
)[:20]


def newest_backup():
    backups = sorted(glob.glob(os.path.join(MODELS, "*_openvino_model_backup*")))
    return backups[-1] if backups else None


def run(model_path, label):
    model = YOLO(model_path, task="detect")
    for _ in range(3):  # warm up: the first call includes OpenVINO compile
        model.predict(
            IMAGES[0], imgsz=480, conf=0.15, iou=0.50, max_det=20,
            device="intel:gpu", verbose=False,
        )
    counts = []
    times = []
    for img in IMAGES:
        t0 = time.perf_counter()
        res = model.predict(
            img, imgsz=480, conf=0.15, iou=0.50, max_det=20, device="intel:gpu",
            verbose=False,
        )[0]
        times.append((time.perf_counter() - t0) * 1000.0)
        counts.append(0 if res.boxes is None else len(res.boxes))
    times_sorted = sorted(times)
    median = times_sorted[len(times_sorted) // 2]
    print(
        f"{label}: total_boxes={sum(counts)} per_image={counts} "
        f"mean_ms={sum(times)/len(times):.1f} median_ms={median:.1f} min_ms={min(times):.1f}"
    )
    return sum(counts), median


if not IMAGES:
    raise SystemExit("no val images found")

fp32_path = sys.argv[1] if len(sys.argv) > 1 else newest_backup()
int8_path = sys.argv[2] if len(sys.argv) > 2 else INT8
if not fp32_path:
    raise SystemExit("no FP32 backup export found; pass the path as arg 1")

print(f"images={len(IMAGES)}")
print(f"fp32={fp32_path}")
print(f"int8={int8_path}")
fp_boxes, fp_ms = run(fp32_path, "FP32")
i8_boxes, i8_ms = run(int8_path, "INT8")
if fp_boxes:
    print(f"box_retention={i8_boxes / fp_boxes * 100:.1f}%  speedup={fp_ms / i8_ms:.2f}x")
