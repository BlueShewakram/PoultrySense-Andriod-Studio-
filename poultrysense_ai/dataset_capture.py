#!/usr/bin/env python3
import argparse
import time
from datetime import datetime
from pathlib import Path

import cv2


CLASS_CHOICES = [
    "normal_standing",
    "crouched",
    "sitting",
    "lying",
    "lethargic",
    "unknown",
]


def parse_args():
    parser = argparse.ArgumentParser(
        description="Capture labeled chicken posture images from the local camera."
    )
    parser.add_argument(
        "--label",
        choices=CLASS_CHOICES,
        default="unknown",
        help="Posture class to capture right now.",
    )
    parser.add_argument(
        "--output-dir",
        default="dataset",
        help="Directory where captured images will be saved.",
    )
    parser.add_argument(
        "--camera-index",
        type=int,
        default=0,
        help="Camera index to open (0, 1, 2, ...).",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=1.0,
        help="Seconds between captures.",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=50,
        help="How many frames to save for this label.",
    )
    parser.add_argument(
        "--show-window",
        action="store_true",
        help="Display the live camera feed while capturing.",
    )
    return parser.parse_args()


def ensure_dirs(output_dir: Path):
    for label in CLASS_CHOICES:
        (output_dir / label).mkdir(parents=True, exist_ok=True)


def main():
    args = parse_args()
    output_dir = Path(args.output_dir)
    ensure_dirs(output_dir)

    cap = cv2.VideoCapture(args.camera_index)
    if not cap.isOpened():
        raise SystemExit(f"Could not open camera index {args.camera_index}. Check camera permissions.")

    label_dir = output_dir / args.label
    print(f"Capturing {args.label} frames to {label_dir}")
    print(f"Press Ctrl+C to stop early.")

    saved = 0
    try:
        while saved < args.count:
            ok, frame = cap.read()
            if not ok:
                time.sleep(0.1)
                continue

            if args.show_window:
                cv2.imshow("PoultrySense posture capture", frame)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    print("User stopped capture.")
                    break

            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            filepath = label_dir / f"{args.label}_{timestamp}_{saved:04d}.jpg"
            cv2.imwrite(str(filepath), frame)
            saved += 1

            print(f"Saved {saved}/{args.count}: {filepath.name}")
            time.sleep(args.interval)
    finally:
        cap.release()
        if args.show_window:
            cv2.destroyAllWindows()

    print(f"Done. Captured {saved} frames in {label_dir}")


if __name__ == "__main__":
    main()
