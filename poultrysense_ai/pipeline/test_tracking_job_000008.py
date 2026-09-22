"""Test whether ByteTrack boxes really track and stay on chickens.

Runs the segmentation model + ByteTrack on job_000008_400frames.mp4 and
evaluates the tracks against the dataset's own tracked-box annotations
(input_videos/annotations_000008.json) with IoU matching.

Metrics:
  - detection recall: fraction of ground-truth chickens matched per frame
  - box quality: IoU of matched predicted boxes vs ground truth
  - ID stability: number of times a predicted track ID switches to a
    different ground-truth chicken while its box stays on the same object
  - track continuity: longest unbroken run for each predicted ID
"""

import json
import os
import sys
import cv2
import numpy as np
from ultralytics import YOLO

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "..", "models", "PoultrySense_ChickenDet_v3_26n.pt")
TRACKER = os.path.join(HERE, "bytetrack.yaml")
VIDEO = os.path.join(HERE, "..", "input_videos", "job_000008_400frames.mp4")
ANN = os.path.join(HERE, "..", "input_videos", "annotations_000008.json")
OUT_VIDEO = os.path.join(HERE, "..", "output", "tracking_000008_annotated.mp4")
OUT_JSON = os.path.join(HERE, "..", "output", "tracking_000008_results.json")

IOU_MATCH = 0.5


def iou(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    a_area = (ax2 - ax1) * (ay2 - ay1)
    b_area = (bx2 - bx1) * (by2 - by1)
    union = a_area + b_area - inter
    return inter / union if union > 0 else 0.0


def match(pred_boxes, gt_boxes):
    """Greedy IoU matching. Returns list of (pred_idx, gt_idx) pairs."""
    pairs = []
    used_pred, used_gt = set(), set()
    for pi, pb in enumerate(pred_boxes):
        best, best_iou = None, IOU_MATCH
        for gi, gb in enumerate(gt_boxes):
            if gi in used_gt:
                continue
            v = iou(pb, gb)
            if v > best_iou:
                best, best_iou = gi, v
        if best is not None:
            pairs.append((pi, best))
            used_pred.add(pi)
            used_gt.add(best)
    return pairs


def main():
    model_path = sys.argv[1] if len(sys.argv) > 1 else MODEL
    ann = json.load(open(ANN))
    gt_by_frame = {
        f["frame_index"]: [
            (float(b[0]), float(b[1]), float(b[2]), float(b[3]), int(b[4]))
            for b in f.get("tracks_bbox") or []
        ]
        for f in ann["frames"]
    }
    fps = ann.get("fps") or 25.0

    model = YOLO(model_path)
    cap = cv2.VideoCapture(VIDEO)
    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    writer = cv2.VideoWriter(OUT_VIDEO, cv2.VideoWriter_fourcc(*"mp4v"),
                             fps, (W, H))

    # per-predicted-ID history: list of (frame, gt_chicken_id) it was matched to
    id_gt_map = {}   # pred_id -> last gt id it followed
    id_frames = {}   # pred_id -> [frames seen]
    id_switches = {} # pred_id -> number of switches to a different gt object
    gt_seen = set()

    frame_idx = 0
    per_frame = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break

        r = model.track(
            frame, persist=True, conf=0.35, iou=0.5, imgsz=960,
            tracker=TRACKER, max_det=16, verbose=False,
        )[0]
        boxes = r.boxes
        pred = []
        if boxes is not None and len(boxes) > 0:
            xyxy = boxes.xyxy.cpu().numpy()
            ids = boxes.id.cpu().numpy() if boxes.id is not None else np.arange(len(xyxy))
            for b, tid in zip(xyxy, ids):
                pred.append((float(b[0]), float(b[1]), float(b[2]), float(b[3]), int(tid)))

        gt = gt_by_frame.get(frame_idx, [])

        # --- evaluation vs ground truth ---
        pairs = match([p[:4] for p in pred], [g[:4] for g in gt])
        matched_gt = set()
        matched_pred = set()
        frame_ious = []
        for pi, gi in pairs:
            pred_id = pred[pi][4]
            gt_id = gt[gi][4]
            matched_gt.add(gi)
            matched_pred.add(pi)
            frame_ious.append(iou(pred[pi][:4], gt[gi][:4]))
            gt_seen.add(gt_id)
            # ID-switch check: is this pred_id now following a different gt chicken?
            prev = id_gt_map.get(pred_id)
            if prev is not None and prev != gt_id:
                id_switches[pred_id] = id_switches.get(pred_id, 0) + 1
            id_gt_map[pred_id] = gt_id
        for p in pred:
            id_frames.setdefault(p[4], []).append(frame_idx)
            if p[4] not in matched_pred:
                # predicted box present but matched to nothing -> its identity
                # is now ambiguous; record a "switch to nothing" only if it was
                # tracking something before and now the box is orphaned
                prev = id_gt_map.get(p[4])
                if prev is not None:
                    pass  # not counted as a switch; it may be a false positive

        recall = len(matched_gt) / len(gt) if gt else (1.0 if not gt else 0.0)
        per_frame.append({
            "frame": frame_idx,
            "pred_count": len(pred),
            "gt_count": len(gt),
            "matched": len(pairs),
            "recall": recall,
            "mean_iou": float(np.mean(frame_ious)) if frame_ious else None,
            "pred": [{"box": p[:4], "track_id": p[4]} for p in pred],
            "gt": [{"box": g[:4], "gt_id": g[4]} for g in gt],
        })

        # --- annotate ---
        anno = frame.copy()
        # ground truth boxes (dashed red) for reference
        for g in gt:
            x1, y1, x2, y2, gid = g
            cv2.rectangle(anno, (int(x1), int(y1)), (int(x2), int(y2)),
                          (0, 0, 255), 1)
            cv2.putText(anno, f"GT {int(gid)}", (int(x1), int(y1) - 5),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
        # predicted tracks (solid colors)
        for p in pred:
            x1, y1, x2, y2, tid = p
            color = ((tid * 67) % 256, (tid * 131) % 256, (tid * 197) % 256)
            cv2.rectangle(anno, (int(x1), int(y1)), (int(x2), int(y2)), color, 3)
            cv2.putText(anno, f"ID {int(tid)}", (int(x1), int(y2) + 18),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        writer.write(anno)

        frame_idx += 1
        if frame_idx % 100 == 0:
            print(f"  processed {frame_idx} frames")

    cap.release()
    writer.release()

    # --- aggregate ---
    total_gt = sum(p["gt_count"] for p in per_frame)
    total_matched = sum(p["matched"] for p in per_frame)
    all_ious = [v for p in per_frame for v in [p["mean_iou"]] if v is not None]
    recall = total_matched / total_gt if total_gt else 0.0
    mean_iou = float(np.mean(all_ious)) if all_ious else 0.0

    # per-pred-ID stats: frames, span, switches
    id_stats = []
    for pid, frames in id_frames.items():
        cont_run = longest = 1
        for a, b in zip(frames, frames[1:]):
            cont_run = cont_run + 1 if b == a + 1 else 1
            longest = max(longest, cont_run)
        id_stats.append({
            "track_id": pid,
            "frames_seen": len(frames),
            "first_frame": frames[0],
            "last_frame": frames[-1],
            "span_frames": frames[-1] - frames[0] + 1,
            "longest_run": longest,
            "id_switches": id_switches.get(pid, 0),
        })

    result = {
        "video": os.path.basename(VIDEO),
        "model": os.path.basename(model_path),
        "frames": len(per_frame),
        "detection_recall": round(recall, 4),
        "mean_iou_matched": round(mean_iou, 4),
        "total_gt_boxes": total_gt,
        "total_matched_boxes": total_matched,
        "n_pred_tracks": len(id_stats),
        "track_stats": sorted(id_stats, key=lambda x: -x["frames_seen"]),
        "per_frame": per_frame,
    }

    with open(OUT_JSON, "w") as f:
        json.dump(result, f, indent=1)

    print("\n===== TRACKING EVALUATION: job_000008_400frames =====")
    print(f"model: {os.path.basename(model_path)}")
    print(f"frames: {len(per_frame)}")
    print(f"detection recall (IoU>{IOU_MATCH}): {recall:.3f}")
    print(f"mean IoU of matched boxes: {mean_iou:.3f}")
    print(f"total predicted tracks: {len(id_stats)}")
    print("\ntrack summary (longest-living first):")
    print(f"{'ID':>4} {'frames':>7} {'first':>6} {'last':>6} {'span':>6} {'longest run':>12} {'switches':>9}")
    for s in id_stats[:12]:
        print(f"{s['track_id']:>4} {s['frames_seen']:>7} {s['first_frame']:>6} "
              f"{s['last_frame']:>6} {s['span_frames']:>6} {s['longest_run']:>12} "
              f"{s['id_switches']:>9}")
    print("\nAnnotated video saved to", OUT_VIDEO)
    print("JSON saved to", OUT_JSON)


if __name__ == "__main__":
    main()
