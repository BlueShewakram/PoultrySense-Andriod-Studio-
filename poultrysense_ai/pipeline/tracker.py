"""PoultrySense Phase 8 - Chicken tracking with ByteTrack.

Runs the segmentation model on a video, tracks individual chickens,
and assigns persistent IDs across frames.
"""

import os
import cv2
import numpy as np
from ultralytics import YOLO

TRACKER_YAML = os.path.join(os.path.dirname(__file__), "bytetrack.yaml")

TRACKER_CFG = {
    "tracker_type": "bytetrack",
    "track_high_thresh": 0.5,
    "track_low_thresh": 0.1,
    "new_track_thresh": 0.6,
    "track_buffer": 40,
    "match_thresh": 0.8,
    "fuse_score": True,
}


def run_tracking(model_path, video_path, out_path=None, conf=0.4, imgsz=640,
                 max_det=10, verbose=False):
    """Run YOLOv8 segmentation + ByteTrack on a video.

    Returns a list of per-frame tracking results.
    """
    model = YOLO(model_path)
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    n_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    writer = None
    if out_path:
        writer = cv2.VideoWriter(out_path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))

    frame_results = []
    frame_idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break

        results = model.track(
            frame, persist=True, conf=conf, iou=0.5, imgsz=imgsz,
            tracker=TRACKER_YAML, max_det=max_det, verbose=verbose,
        )
        r = results[0]
        boxes = r.boxes
        ids = boxes.id if boxes is not None and boxes.id is not None else None

        dets = []
        if boxes is not None and len(boxes) > 0:
            xyxy = boxes.xyxy.cpu().numpy()
            confs = boxes.conf.cpu().numpy()
            ids_n = ids.cpu().numpy() if ids is not None else np.arange(len(xyxy))
            for i, box in enumerate(xyxy):
                dets.append({
                    "frame": frame_idx,
                    "track_id": int(ids_n[i]),
                    "conf": float(confs[i]),
                    "box": [float(v) for v in box],
                })
        frame_results.append({"frame": frame_idx, "detections": dets})

        if writer is not None:
            annotated = frame.copy()
            for d in dets:
                x1, y1, x2, y2 = [int(v) for v in d["box"]]
                color = (int(255 * (d["track_id"] % 3 == 0)),
                         int(255 * (d["track_id"] % 3 == 1)),
                         int(255 * (d["track_id"] % 3 == 2)))
                cv2.rectangle(annotated, (x1, y1), (x2, y2), color, 2)
                label = f"ID {d['track_id']}"
                cv2.putText(annotated, label, (x1, max(15, y1 - 5)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)
            writer.write(annotated)

        frame_idx += 1
        if not verbose and frame_idx % 50 == 0:
            print(f"  tracked {frame_idx}/{n_frames} frames")

    cap.release()
    if writer is not None:
        writer.release()

    return {"fps_in": fps, "n_frames": frame_idx, "frames": frame_results}


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser(description="PoultrySense tracking")
    p.add_argument("video", help="input video path")
    p.add_argument("--out", default=None, help="output annotated video path")
    p.add_argument("--model", default=os.path.join(
        os.path.dirname(__file__), "..", "models", "PoultrySense_YOLOv8_ChickenSeg.pt"))
    p.add_argument("--conf", type=float, default=0.4)
    args = p.parse_args()

    res = run_tracking(args.model, args.video, out_path=args.out, conf=args.conf)
    print(f"Tracked {res['n_frames']} frames at {res['fps_in']:.1f} fps")
    if args.out:
        print("Annotated video saved to", args.out)