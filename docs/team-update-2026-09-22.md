# PoultrySense — Team Update (22 Sep 2026)

What changed today, why, and what's next. Written so everyone (even non-ML members) can follow.

---

## 1. Posts & dataset basics (concepts, no code)
- **`.pt` files are trained models, NOT datasets.** A dataset (photos + labels) is study material used once during training (in Colab); the `.pt` is the graduate that runs on the farm. Our app only ever needs `.pt` files to scan chickens.
- Consequence: no dataset is needed on any machine for *running* the app — only for *retraining*.

## 2. Friend's dataset: staged and measured
- Received `PoultrySense_Datasets_and_Weights.zip` (886 MB): **9,606 labeled chicken photos** (Roboflow "chicken-counting", single class `chicken`) + finished `.pt` weights from a previous training run.
- Staged locally at `poultrysense_ai/datasets/chicken_det/` (git-ignored, never pushed — 886 MB stays off GitHub). Fixed its broken config paths, validated all 4,803 labels (0 missing, 0 malformed).
- The ZIP also contained trained weights — including the model that became our new detector (see §4).

## 3. Detector scoreboard (all measured, same 1,103-image test, same settings)
| Model | Precision | Recall | mAP50 | Verdict |
|---|---|---|---|---|
| A — our old `ChickenDet.pt` (YOLO11n, D-sets) | 0.48 | 0.36 | 0.22 | **REMOVED** (files + code refs deleted; missed 64% of chickens in crowds) |
| B — friend's `best.pt` → was v2 (YOLO11n, Roboflow 9.6k) | 0.72 | 0.65 | 0.64 | **REMOVED** (was champion, replaced; files + code refs deleted) |
| D — random HuggingFace model (24 training photos) | 0.25 | 0.23 | 0.13 | Rejected (pretty demo image, terrible numbers — measure, don't browse) |
| OV-B — v2 via OpenVINO | 0.72 | 0.64 | 0.64 | Historical: 8.3× faster (31 ms vs 258 ms), zero accuracy loss |
| **26 — YOLO26n fine-tune (our Colab, Roboflow 3.7k)** | **0.85** | **0.78** | **0.77** | **CHAMPION, DEPLOYED as v3** |
| OV-26 — v3 via OpenVINO | 0.82 | 0.78 | 0.76 | Same accuracy (−0.008, noise), **32 ms/frame. This is what runs now.** |

## 4. Deployed: ChickenDet v3 (YOLO26n, our Colab training)
- `poultrysense_ai/models/PoultrySense_ChickenDet_v3_26n.pt` (5.1 MB) loads first and only. **Old detectors removed everywhere**: v1 + Seg + v2 files deleted from disk, `DET_MODELS` cleaned, v2 removed from git, dead download entries pruned. Survivors: v3 + generic `yolo11n.pt` emergency fallback (loads only if v3 is missing) + behavior/health classifiers (different job, untouched).
- **Why v3:** +0.13 mAP50, +0.13 recall over v2 at 3× the torch speed. The combine-both-educations idea worked (modern arch + friend's data).

## 5. Tracker upgraded: ByteTrack → BoT-SORT
- **Why:** ByteTrack matches by motion only, so IDs die when chickens deform/occlude while pecking. BoT-SORT adds **appearance (ReID) memory** — it remembers what each bird looks like, so IDs survive occlusion.
- Same thresholds carried over; camera-motion compensation OFF (our camera is static — it would burn CPU for nothing); strict appearance matching (same-breed birds look alike, so loose matching would fuse birds).
- Old config kept on disk = one-line rollback. Watch `/status` FPS after restart (ReID costs some CPU).

## 6. Speed: OpenVINO on Intel (free, 8.3× faster)
- YOLO now runs through Intel's free OpenVINO runtime instead of plain PyTorch-CPU: **31 ms vs 258 ms per frame**, zero accuracy loss (scoreboard proves it).
- One-time auto-export per model (cached on disk); if exports ever break, the app silently falls back to `.pt` (rollback = delete folders). Detection now fits inside the 33 ms frame budget.

## 7. Tracking stability fixes (already in the pipeline)
- 3-hit debounce before a box appears (no one-frame phantom pops), 5-frame coasting before it disappears (no single-frame blinks), every-2nd-frame inference schedule, spatial re-ID up to 130 px, matcher loosened 0.75 → 0.5 for deforming chickens, max 10 birds, confident-only display.
- New **pixel-churn gate**: static screen regions (paused video, UI icons) can never confirm as chickens; real camera footage always passes. Stops the "6 birds on a phone screen" problem.

## 8. App fix: subject numbers now match
- The circled number on each tracked-bird card used to show list position while the title showed the real track ID (row #1 = "Chicken #2"). Both now show the track ID. (Rebuild/reinstall the app to see it.)

## 9. Gemini: fixed + verified, then secured (architecture finalized)
- Found Google had **retired `gemini-2.5-flash`** — every AI escalation was silently failing over to local heuristics. Switched to `gemini-3.6-flash`, live-tested (`CONNECTED`). All log labels corrected (they still said "2.5" after the fix — now honest).
- Final architecture: YOLO26n detector (OpenVINO) → BoT-SORT with ReID → per-bird behavior history → 5-signal anomaly score → Gemini verdict engine (now verdicts every tracked chicken with adaptive cooldown, can override heuristics) → phone badges + `/status` + `/api/alerts`.
- **Security incident:** the real API key was committed to GitHub. It is now untracked (`.gitignore` + pushed fix), and rotation was verified live (old key → 401 dead, new key → `CONNECTED`). **Everyone: use personal keys, never share, never commit.**

## 10. YOLO26 switch — DONE (was "in progress" this morning)
- Sandbox passed (`yolo26n.pt` loads, genuine NMS-free `end2end`/`one2one` head verified), smoke-tested training locally, then the real 40-epoch Colab T4 run on 3,700 images.
- Result: mAP50 **0.77** (see §3). Deployed as v3. Our datasets transfer as-is across architectures; old weights can't (that's why we retrained, not converted). Contender C (fine-tune 11n) skipped as obsolete.

## 11. ChickenVerse dataset — TEAM DIRECTIVE: USE IT FOR THE NEXT TRAINING
- Evaluated `amirivojdan/ChickenVerse` (UTennessee, academic): 6,539 imgs / 153,764 birds, dense overhead commercial houses (23 birds/photo — our exact weakness) + 15k behavior video clips across 15 behaviors. Different and bigger than the Roboflow set.
- Caveats: non-commercial license (**CC BY-NC-SA** — fine for our farms/research, do NOT publish trained weights commercially); needs COCO→YOLO conversion (converter ready: `poultrysense_ai/training/scripts/coco_to_yolo.py`).
- **What the team must do, in order:**
  1. **Download** it from Zenodo (link in their README) — whoever has bandwidth first.
  2. **Convert**: `python poultrysense_ai/training/scripts/coco_to_yolo.py --src <download> --dst poultrysense_ai/datasets/chickendet_cv --splits train val test`
  3. **Stage it as a dataset** next to `chicken_det/` (git-ignored, same layout, never pushed).
  4. **Do NOT train yet** — first we eval current v3 on their held-out commercial farm (free honesty check). Training order comes after that scoreboard.
  5. ChickenAct (behavior clips) is phase 2 — needs a video pipeline we don't have yet. Detector work first.

## 12. Verification: everything above was actually tested today
- Compile checks on all edited Python files; `DET_MODELS` resolves v3 first; OpenVINO cached export used.
- Post-cleanup re-val: OV-26 = P .82 / R .78 / mAP50 .76 (identical to pre-cleanup — cleanup broke nothing).
- BoT-SORT + v3 smoke: boxes with persistent IDs on test image; ReID brain (`yolo26n-cls.pt`) wired into `download_models.py` for fresh machines.
- Junk removed: stale Intel GPU error logs, duplicate ReID weight copy, dead download entries (v2/v1 release URLs that never existed).

## 13. Pending / action items
- [ ] Validate live: restart server → console shows v3 path → 2–3 min footage → boxes stick? IDs hold? FPS healthy?
- [ ] Rebuild + reinstall the Android app (numbering fix needs it)
- [ ] ChickenVerse: download → convert → stage (directive in §11); eval v3 on their held-out farm before any training call
- [ ] Friend: clone gets v3 + all code automatically; needs own Gemini key + `python download_models.py` for the rest

*Full numbers live in `poultrysense_ai/training/SCOREBOARD.md`. Rollback for anything above: most fixes revert in under 5 minutes (delete export folders, reorder two config lines, or `git revert`).*
