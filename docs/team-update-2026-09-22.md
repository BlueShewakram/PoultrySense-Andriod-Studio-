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
| A — our old `ChickenDet.pt` | 0.48 | 0.36 | 0.22 | Retired to fallback (missed 64% of chickens in crowds — the vanishing-box bug) |
| **B — friend's `best.pt` → deployed as v2** | **0.72** | **0.65** | **0.64** | **Current champion, deployed** |
| D — random HuggingFace model (24 training photos) | 0.25 | 0.23 | 0.13 | Rejected (pretty demo image, terrible numbers — measure, don't browse) |
| OV-B — v2 via OpenVINO | 0.72 | 0.64 | 0.64 | Same accuracy, **8.3× faster** (31 ms vs 258 ms/frame) |

## 4. Deployed: ChickenDet v2 (YOLO11n-based, friend's training)
- `poultrysense_ai/models/PoultrySense_ChickenDet_v2.pt` loads first; old model stays as automatic fallback. Nothing was deleted. Pushed to GitHub so every clone gets it.
- **Why:** nearly 2× the recall of our old model in crowded scenes — directly fixes disappearing boxes.

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

## 9. Gemini: fixed + verified, then secured
- Found Google had **retired `gemini-2.5-flash`** — every AI escalation was silently failing over to local heuristics. Switched to `gemini-3.6-flash`, live-tested (`CONNECTED`).
- **Security incident:** the real API key was committed to GitHub. It is now untracked (`.gitignore` + pushed fix). **Everyone: the old key must be deleted in Google AI Studio; use personal keys, never share, never commit.**
- How Gemini fits: YOLO models + anomaly math judge every bird continuously (free); Gemini is woken only for score ≥ 60 cases to write the farmer-facing reason + recommendation. No score ≥ 60 lately = correctly silent, not broken.

## 10. YOLO26 (next detector generation) — in progress
- Verified YOLO26 is real and suits us (NMS-free, up to 43% faster CPU, OpenVINO-native). Proved `yolo26n.pt` loads in our setup; smoke-tested training on our data.
- **Running now:** 40-epoch fine-tune on the friend's 3,700 images in Colab (GPU). When `best.pt` returns → same scoreboard → deploys only on a win, else v2 stays. Our datasets transfer as-is; old weights can't transfer across architectures (that's why we retrain, not convert).

## 11. ChickenVerse dataset (future fuel, not the same data)
- Evaluated `amirivojdan/ChickenVerse` (UTennessee, academic): 6,539 imgs / 153,764 birds, dense overhead commercial houses + 15k behavior video clips. Different and bigger than the Roboflow set — genuine gold for later phases (brutally honest cross-farm testing + behavior-model upgrade).
- Caveats: non-commercial license (CC BY-NC-SA), needs format conversion (converter script ready at `poultrysense_ai/training/scripts/coco_to_yolo.py`). Parked until 26n ships — one race at a time.

## 12. Pending / action items
- [ ] Delete the exposed Gemini key in Google AI Studio; each member uses their own key
- [ ] Validate live: restart server → 2–3 min real footage → boxes stick? IDs hold? FPS healthy?
- [ ] Rebuild + reinstall the Android app (numbering fix needs it)
- [ ] Finish Colab 26n run → send back `best.pt` + score line
- [ ] Friend: prep ChickenVerse (download + convert), train only after the 26n verdict

*Full numbers live in `poultrysense_ai/training/SCOREBOARD.md`. Rollback for anything above: most fixes revert in under 5 minutes (delete export folders, reorder two config lines, or `git revert`).*
