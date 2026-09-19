# PoultrySense posture dataset

This folder is for collecting labeled posture images from the live camera so a custom posture model can be trained later.

## Recommended labels

- `normal_standing`
- `crouched`
- `sitting`
- `lying`
- `lethargic`
- `unknown`

## Capture workflow

Run the capture script once per posture class. Example:

```bash
cd /Users/iamyababa/Desktop/PSense/PoultrySense-Andriod-Studio-/poultrysense_ai
./venv/bin/python dataset_capture.py --label normal_standing --count 100 --interval 1.0 --show-window
```

Then repeat for the other classes:

```bash
./venv/bin/python dataset_capture.py --label lying --count 100 --interval 1.0 --show-window
./venv/bin/python dataset_capture.py --label lethargic --count 100 --interval 1.0 --show-window
```

## Important note

This is the first step in building a custom posture dataset. The app is currently using a pretrained YOLO detector plus rule-based posture heuristics. This capture workflow is for creating training data for a stronger posture-specific model later.
