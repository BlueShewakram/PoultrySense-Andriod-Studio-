# CVAT workflow for collecting and labeling chicken images

This is the easiest large-dataset route if you want a real poultry detection model instead of relying on generic bird detections.

## 1) Collect chicken images

Use your camera, phone, or existing farm clips and save a variety of frames:

- front view
- side view
- walking / standing / feeding / resting
- different distances from camera
- daylight and dim-light scenes
- multiple chickens in one frame
- partially visible birds are okay, but box only what is visible

Aim for a minimum of 100–300 images. Better results come from 500+ labeled images.

## 2) Create a CVAT task

1. Open https://cvat.ai/
2. Sign in and create a new project
3. Create a task
4. Upload your collected images as a zip or folder
5. Use label name: `chicken`

## 3) Annotate the images

Use the rectangle tool and draw one box around each visible chicken.

Rules:

- one box per chicken
- include the whole bird if visible
- if a bird is partially hidden, box only the visible portion
- do not label background objects
- keep boxes tight but not tiny or cut off

## 4) Export as YOLO 1.1

In CVAT:

1. Open the task
2. Select Export
3. Choose `YOLO 1.1` or `YOLO` format
4. Download the export zip

## 5) Prepare the exported dataset for this project

After export, use the helper script:

```bash
cd /Users/iamyababa/Desktop/PSense/PoultrySense-Andriod-Studio-/poultrysense_ai
./venv/bin/python training/scripts/prepare_cvat_yolo_dataset.py \
  --source /path/to/cvat_export \
  --out training/datasets/chicken_det
```

This will create the folder layout expected by the trainer:

```text
training/datasets/chicken_det/
├── data.yaml
├── images/
│   ├── train/
│   └── val/
├── labels/
│   ├── train/
│   └── val/
```

## 6) Train the model

```bash
cd /Users/iamyababa/Desktop/PSense/PoultrySense-Andriod-Studio-/poultrysense_ai
./venv/bin/python training/scripts/train_chicken_detector.py \
  --data training/datasets/chicken_det \
  --epochs 60 \
  --batch 16
```

## 7) Use the trained model

After training finishes, the script writes the best weights to:

```text
training/models/chicken_detector.pt
```

Then point the live server at it with:

```bash
export POULTRYSENSE_MODEL_PATH=/Users/iamyababa/Desktop/PSense/PoultrySense-Andriod-Studio-/poultrysense_ai/training/models/chicken_detector.pt
```

## 8) YOLO format example

A label text file looks like this:

```text
0 0.359 0.486 0.250 0.472
```

This means:

- `0` = chicken class ID
- `0.359` = normalized center x
- `0.486` = normalized center y
- `0.250` = normalized width
- `0.472` = normalized height

The project expects a single class named `chicken`.

## 9) Keep dataset quality high

Use 70–80% of images for train and 20–30% for validation. Avoid training on only one background or a single pose. The more realistic the variety, the better the model will generalize to your coop.
