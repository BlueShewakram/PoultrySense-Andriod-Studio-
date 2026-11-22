
import cv2
import time

cap = cv2.VideoCapture(0, cv2.CAP_MSMF)

if not cap.isOpened():
    print("Camera failed to open!")
    exit()

start = time.time()
frames = 0
failed = 0

print("Testing camera for 60 seconds...")

while time.time() - start < 60:
    ret, frame = cap.read()

    if ret:
        frames += 1
    else:
        failed += 1

elapsed = time.time() - start

print("\n===== CAMERA RESULTS =====")
print(f"Camera Index: 0")
print(f"Duration: {elapsed:.2f} seconds")
print(f"Successful Frames: {frames}")
print(f"Failed Frames: {failed}")
print(f"Actual Capture FPS: {frames / elapsed:.2f}")

cap.release()
