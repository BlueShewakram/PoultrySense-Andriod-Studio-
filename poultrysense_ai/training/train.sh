#!/usr/bin/env bash
# Behavior / posture health training helpers (not fecal).
set -euo pipefail
TRAIN_DIR="$(cd "$(dirname "$0")" && pwd)"
PY="$TRAIN_DIR/../venv/bin/python"
cd "$TRAIN_DIR"

case "${1:-help}" in
  init)
    exec "$PY" scripts/prepare_behavior_dataset.py --init
    ;;
  capture)
    shift
    exec "$PY" scripts/capture_behavior_crops.py "$@"
    ;;
  prepare)
    shift
    exec "$PY" scripts/prepare_behavior_dataset.py --from-captures "$@"
    ;;
  train)
    shift
    exec "$PY" scripts/train_behavior_classifier.py "$@"
    ;;
  detector)
    shift
    exec "$PY" scripts/train_chicken_detector.py "$@"
    ;;
  predict)
    shift
    exec "$PY" scripts/predict_behavior.py "$@"
    ;;
  *)
    cat <<EOF
PoultrySense poultry detection + behavior/posture health

Live scoring (no training needed):
  Uses motion + posture geometry in poultrysense_ai/behavior_health.py

Optional custom detector training:
  ./train.sh detector --data ../datasets/chicken_det --epochs 60

Optional classifier training from chicken crops:
  ./train.sh init
  ./train.sh capture --seconds 90 --classes 14,0
  ./train.sh prepare
  ./train.sh train --epochs 40
  ./train.sh predict path/to/crop.jpg

Classes:
  chicken (detector) | normal / lethargic / abnormal_posture (classifier)
EOF
    ;;
esac
