#!/usr/bin/env python3
"""
Capture short per-track behavior image sequences from a running PoultrySense AI server.

Polls `/frame.jpg` and `/status` and saves cropped subject sequences keyed by
`track_id`. Useful for collecting behavior/posture examples without manual
camera capture wiring.

Usage example:
  ./capture_behavior_sequences.py --server http://127.0.0.1:5050 --out data/sequences --seq-len 16

"""
from __future__ import annotations

import argparse
import json
import time
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Dict

import cv2
import numpy as np
import requests


def parse_args():
    parser = argparse.ArgumentParser(description="Capture per-track behavior sequences from PoultrySense server.")
    parser.add_argument("--server", default="http://127.0.0.1:5050", help="Base URL of running PoultrySense AI server")
    parser.add_argument("--out", default="sequences", help="Output directory for saved sequences")
    parser.add_argument("--seq-len", type=int, default=16, help="Number of frames per saved sequence")
    parser.add_argument("--interval", type=float, default=0.1, help="Seconds between polls")
    parser.add_argument("--min-confidence", type=float, default=0.2, help="Minimum detection confidence to consider")
    parser.add_argument("--max-tracks", type=int, default=128, help="Maximum distinct tracks to buffer")
    return parser.parse_args()


def decode_frame(resp_content: bytes):
    arr = np.frombuffer(resp_content, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    return img


def crop_from_bbox(img, bbox):
    # Expect bbox as [x, y, w, h]
    x, y, w, h = map(int, bbox)
    h_img, w_img = img.shape[:2]
    x1 = max(0, x)
    y1 = max(0, y)
    x2 = min(w_img, x + w)
    y2 = min(h_img, y + h)
    if x2 <= x1 or y2 <= y1:
        return None
    return img[y1:y2, x1:x2]


def main():
    args = parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    seq_len = args.seq_len
    buffers: Dict[int, deque] = {}
    saved_counter = 0

    frame_url = args.server.rstrip('/') + '/frame.jpg'
    status_url = args.server.rstrip('/') + '/status'

    print(f"Polling {frame_url} and {status_url}; saving sequences to {out_dir}")

    try:
        while True:
            try:
                fresp = requests.get(frame_url, timeout=3)
                sresp = requests.get(status_url, timeout=3)
            except requests.RequestException:
                time.sleep(args.interval)
                continue

            if fresp.status_code != 200 or sresp.status_code != 200:
                time.sleep(args.interval)
                continue

            frame = decode_frame(fresp.content)
            if frame is None:
                time.sleep(args.interval)
                continue

            try:
                status = sresp.json()
            except Exception:
                time.sleep(args.interval)
                continue

            detections = status.get('detections') or []

            for det in detections:
                conf = det.get('confidence') or det.get('conf') or 0.0
                if conf < args.min_confidence:
                    continue

                track_id = det.get('track_id')
                if track_id is None:
                    continue

                bbox = det.get('bbox')
                if not bbox:
                    continue

                crop = crop_from_bbox(frame, bbox)
                if crop is None:
                    continue

                if track_id not in buffers:
                    # limit overall tracked subject buffers
                    if len(buffers) >= args.max_tracks:
                        # drop the oldest track buffer
                        oldest = sorted(buffers.items(), key=lambda kv: len(kv[1]))[0][0]
                        buffers.pop(oldest, None)
                    buffers[track_id] = deque(maxlen=seq_len)

                ts = datetime.utcnow().isoformat() + 'Z'
                buffers[track_id].append({'ts': ts, 'image': crop, 'bbox': bbox, 'confidence': conf, 'class': det.get('class')})

                # If buffer reached full length, persist sequence
                if len(buffers[track_id]) >= seq_len:
                    seq_ts = datetime.utcnow().strftime('%Y%m%d_%H%M%S_%f')
                    seq_dir = out_dir / f'track_{track_id}' / f'seq_{seq_ts}_{saved_counter:04d}'
                    seq_dir.mkdir(parents=True, exist_ok=True)

                    meta = {'track_id': track_id, 'saved_at': seq_ts, 'source': frame_url, 'frames': []}
                    for i, item in enumerate(list(buffers[track_id])):
                        img = item['image']
                        fname = f'{i:03d}.jpg'
                        cv2.imwrite(str(seq_dir / fname), img)
                        meta['frames'].append({'file': fname, 'ts': item['ts'], 'bbox': item['bbox'], 'confidence': item['confidence'], 'class': item.get('class')})

                    # write metadata
                    with open(seq_dir / 'meta.json', 'w') as fh:
                        json.dump(meta, fh, indent=2)

                    print(f"Saved sequence {seq_dir} ({seq_len} frames) for track {track_id}")
                    saved_counter += 1
                    # clear buffer to avoid overlapping saves
                    buffers[track_id].clear()

            time.sleep(args.interval)

    except KeyboardInterrupt:
        print("Interrupted — exiting")


if __name__ == '__main__':
    main()
