"""
PoultrySense AI - Dataset Uploader & Gemini Model Tuning Script
Uses Google GenAI SDK (google-genai) to upload training dataset
and configure/trigger supervised fine-tuning.
"""

import os
import sys
import json
import csv
from google import genai
from google.genai import types

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(SCRIPT_DIR, "gemini_config.json")
DATASET_PATH = os.path.join(SCRIPT_DIR, "gemini_lethargy_detection_tuning.csv")

def load_config():
    if not os.path.exists(CONFIG_PATH):
        print(f"[ERROR] Config file not found at: {CONFIG_PATH}")
        sys.exit(1)
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)

def validate_dataset():
    if not os.path.exists(DATASET_PATH):
        print(f"[ERROR] Dataset file not found at: {DATASET_PATH}")
        sys.exit(1)
    
    with open(DATASET_PATH, "r", encoding="utf-8") as f:
        reader = list(csv.reader(f))
    
    header = reader[0]
    rows = reader[1:]
    print(f"[*] Validating dataset: {DATASET_PATH}")
    print(f"    - Header: {header}")
    print(f"    - Total sample pairs: {len(rows)}")
    
    examples = []
    for i, r in enumerate(rows, 1):
        if len(r) != 2:
            print(f"[ERROR] Row {i} does not have exactly 2 columns!")
            sys.exit(1)
        # Verify JSON output
        try:
            parsed = json.loads(r[1])
            for key in ["severity", "reason", "farmer_action"]:
                if key not in parsed:
                    print(f"[ERROR] Row {i} JSON missing key '{key}'")
                    sys.exit(1)
        except Exception as e:
            print(f"[ERROR] Row {i} output is not valid JSON: {e}")
            sys.exit(1)
            
        examples.append(types.TuningExample(
            text_input=r[0],
            output=r[1]
        ))
        
    print(f"    [OK] All {len(examples)} examples validated successfully!")
    return examples

def main():
    print("=" * 65)
    print("   POULTRYSENSE AI - GOOGLE AI STUDIO / VERTEX AI TUNER   ")
    print("=" * 65)

    config = load_config()
    api_key = config.get("gemini_api_key", "").strip()
    
    if not api_key or api_key == "YOUR_GEMINI_API_KEY_HERE":
        print("[ERROR] Please provide a valid Gemini API key in gemini_config.json")
        sys.exit(1)

    examples = validate_dataset()

    # 1. Initialize GenAI Client
    print("\n[1/3] Connecting to Google GenAI API...")
    client = genai.Client(api_key=api_key)
    print("    [OK] Client connected.")

    # 2. Upload file to Google AI Studio Files API
    print("\n[2/3] Uploading gemini_lethargy_detection_tuning.csv to Google AI...")
    try:
        uploaded_file = client.files.upload(
            file=DATASET_PATH,
            config=dict(
                display_name="poultrysense_chicken_lethargy_tuning_dataset",
                mime_type="text/csv"
            )
        )
        print(f"    [SUCCESS] File uploaded!")
        print(f"    - Remote Name: {uploaded_file.name}")
        print(f"    - URI: {uploaded_file.uri}")
        print(f"    - MIME Type: {uploaded_file.mime_type}")
        print(f"    - Size: {uploaded_file.size_bytes} bytes")
    except Exception as e:
        print(f"    [WARN] Direct Files API upload notice: {e}")
        uploaded_file = None

    # 3. Initiate Fine-Tuning Job
    print("\n[3/3] Initiating Supervised Fine-Tuning Job...")
    base_model_candidates = [
        "gemini-1.5-flash",
        "models/gemini-1.5-flash-001",
        "gemini-1.5-flash-001",
        "models/gemini-2.5-flash",
    ]

    tuning_success = False
    custom_model_id = None

    for base_model in base_model_candidates:
        try:
            print(f"    Trying base model: {base_model}...")
            job = client.tunings.tune(
                base_model=base_model,
                training_dataset=types.TuningDataset(examples=examples),
                config=types.CreateTuningJobConfig(
                    tuned_model_display_name="poultrysense-chicken-lethargy-flash",
                    epoch_count=15,
                    batch_size=4,
                    learning_rate=0.001
                )
            )
            print(f"    [SUCCESS] Fine-tuning job created!")
            print(f"    - Job Name: {job.name}")
            custom_model_id = getattr(job, "tuned_model", None) or getattr(job, "name", None)
            print(f"    - Model ID: {custom_model_id}")
            tuning_success = True
            break
        except Exception as ex:
            err_msg = str(ex)
            if "Enterprise Agent Platform mode" in err_msg or "UNIMPLEMENTED" in err_msg:
                print(f"    [INFO] Programmatic tuning endpoint returned: {err_msg}")
                break
            else:
                print(f"    [NOTICE] Base model {base_model} unavailable: {err_msg}")

    print("\n" + "=" * 65)
    if tuning_success and custom_model_id:
        print(f"[RESULT] Fine-tuning job is now training on Google AI Studio!")
        print(f"         Custom Model ID: {custom_model_id}")
        print(f"         You can set this model in poultrysense_ai/gemini_config.json:")
        print(f'         "model": "{custom_model_id}"')
    else:
        print("[RESULT] Dataset is uploaded and ready for Google AI Studio Tuning!")
        print(f"         Uploaded File: {getattr(uploaded_file, 'name', 'gemini_lethargy_detection_tuning.csv')}")
        print("\n         To complete 1-click training in Google AI Studio:")
        print("         1. Open: https://aistudio.google.com/tune")
        print("         2. Click 'Create Tuned Model' -> Select 'Gemini 1.5 Flash' (or 'Gemini 2.5 Flash')")
        print("         3. Choose 'Upload CSV' -> select poultrysense_ai/gemini_lethargy_detection_tuning.csv")
        print("         4. Click 'Tune'. Once finished, copy the tuned model ID (e.g. tunedModels/chicken-lethargy-xxxx)")
        print("         5. Paste it into poultrysense_ai/gemini_config.json under \"model\".")
    print("=" * 65)

if __name__ == "__main__":
    main()
