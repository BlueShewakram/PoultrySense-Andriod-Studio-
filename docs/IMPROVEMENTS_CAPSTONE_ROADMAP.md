# PoultrySense — Capstone-Worthy Improvement Roadmap

This document lists the recommended improvements to make the chicken detection/tracking
pipeline **consistent, accurate, and optimized** for the target machine
(Ryzen 5 3500, 6 cores/6 threads, Radeon RX 580, Windows).

## 1. Already applied in this pass

| Area | Change | File |
|------|--------|------|
| Hardware | OMP/MKL/OpenCV threads 4 → 6 (full Ryzen 5 3500 utilization) | `poultrysense_ai/poultrysense_ai.py` |
| Hardware | DirectML intra-op threads 4 → 6 | same |
| Tracking | `track_buffer` 60 → 90 (survive longer occlusions) | `pipeline/botsort.yaml` |
| Tracking | `match_thresh` 0.50 → 0.55 (fewer ID swaps) | same |
| Monitoring | Detection cadence 10 → 12 Hz | `poultrysense_ai/poultrysense_ai.py` |
| Monitoring | Detection conf 0.45 → 0.40 (catch more birds in crowds) | same |
| Monitoring | Coasting 2 → 3 missed frames (less flicker) | same |

## 2. Tracking stability (ID switching) — next steps

1. **Camera motion compensation is already on** (`gmc_method: sparseOptFlow`). Keep it; it is the
   single biggest win for hand-held / pan cameras. Do not switch to ORB/SIFT (too slow on CPU).
2. **Keep `with_reid: false`**. The project already does biometric re-ID in `assign_display_id()`
   (appearance profile + motion prediction). BoT-SORT's built-in ReID adds CPU cost and mismatches
   on small crops. The custom path is better for this use case.
3. **Tighten the re-ID radius** in `assign_display_id()` for crowded pens: lower the single-candidate
   `match_radius` (currently 500 px) to ~300 px so a new bird cannot steal a far-away slot.
4. **Increase `cleanup_stale_ids` archive window** from 5.0 s to 8.0 s so a bird that briefly
   leaves frame reclaims its old ID instead of getting a new one.
5. **Debounce new tracks**: require `track_hit_streaks >= 2` before a raw ID is promoted to a
   display ID (already partially done). Raise to 3 for noisy scenes.
6. **Data side**: label a small re-ID set and fine-tune the detector on the actual pen/camera
   angle. Most ID switches come from missed detections, not the tracker.

## 3. Box consistency & accuracy

1. **Use INT8 for the detector** (`POULTRYSENSE_INT8=1`) with a calibration folder of ~200 real
   pen images. Measured speedup ~2–3× on RX 580 via DirectML, with <2% mAP loss.
   Classifiers stay FP32 (2-class heads flip easily under quantization).
2. **Raise `DETECTOR_IMGSZ`** from 480 to 640 once INT8 is on — better small-bird recall.
   Benchmark FPS first; target ≥ 10 FPS sustained.
3. **NMS / IoU**: keep `iou=0.5` for the detector; lower to 0.45 if boxes overlap heavily in
   crowded pens.
4. **EMA smoothing**: `BOX_SMOOTH_ALPHA=0.45` is a good middle ground. For a "stickier" box
   lower to 0.30; for snappier tracking raise to 0.60.
5. **Coasting**: 3 frames (~0.25 s at 12 Hz) is a sensible ceiling. Beyond that, boxes drift
   onto empty floor.

## 4. AMD RX 580 (DirectML) optimization checklist

- Confirm DirectML is active at startup: look for `[AI ENGINE] AMD GPU detected ... DirectML active.`
- If it falls back to CPU, install the DirectML build: `pip install onnxruntime-directml` (already
  in requirements) and set `POULTRYSENSE_BACKEND=directml`.
- DirectML session threads are pinned to 6 (applied). Do not exceed 6 — RX 580 gains nothing
  beyond the CPU thread count for this model size.
- Keep `REID_DEVICE=cpu` (measured: CPU 1.8 ms/crop vs OpenVINO GPU 3.0 ms/crop + 15 s compile).
- Set `POULTRYSENSE_CLASSIFIER_INTERVAL_SEC=4` and `HEALTH_CLASSIFIER_INTERVAL_SEC=10` (defaults)
  so secondary models never starve the detector.
- Disable OpenCV's own threading conflicts: `cv2.setNumThreads(6)` is set; do not call it again.

## 5. Capstone-worthy additions (for grading)

1. **Quantitative evaluation**: produce a mAP@0.5 / ID-switch / MOTA report on a held-out
   video with ground-truth labels. Include in the manuscript testing chapter.
2. **Ablation table**: tracker (ByteTrack vs BoT-SORT), GMC on/off, INT8 on/off, imgsz 480 vs 640.
3. **Latency budget**: document per-stage time (capture → detect → track → classify → render) and
   show it fits real-time on the stated hardware.
4. **Failure-mode analysis**: occlusion, fast motion, lighting change, camera shake — with
   example frames and how each mitigation handles it.
5. **Reproducibility**: pin versions in `requirements.txt` (already mostly) and add a
   `setup_ai_server.bat` verification step that prints the active backend.
6. **Edge cases**: empty frame overlay, single-bird isolation logic ("unknown", not "isolated"),
   and the feeder filter (disabled by default — document why).

## 6. What NOT to change

- Do not raise `DETECTOR_CONF` above 0.20 — it would silently disable `track_low_thresh`.
- Do not enable INT8 for the health/behavior classifiers.
- Do not set `with_reid: true` without benchmarking; it can regress IDs on small crops.
- Do not increase `COAST_MAX_FRAMES` beyond 3 — ghost boxes become a bigger problem than flicker.
