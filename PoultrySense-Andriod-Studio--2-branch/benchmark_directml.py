import time
import numpy as np
import onnxruntime as ort

model_path = r"poultrysense_ai\models\PoultrySense_ChickenDet_v3_26n.onnx"

print("Available providers:", ort.get_available_providers())

# Configure DirectML
options = ort.SessionOptions()
options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
options.enable_mem_pattern = False

# Create DirectML session
session = ort.InferenceSession(
    model_path,
    sess_options=options,
    providers=["DmlExecutionProvider"]
)

print("Session providers:", session.get_providers())

# Automatically inspect model input
model_input = session.get_inputs()[0]
input_name = model_input.name

shape = [
    dim if isinstance(dim, int) and dim > 0 else 640
    for dim in model_input.shape
]

# Use batch size 1
shape[0] = 1

if model_input.type == "tensor(float)":
    dtype = np.float32
elif model_input.type == "tensor(float16)":
    dtype = np.float16
else:
    raise ValueError(f"Unsupported input type: {model_input.type}")

dummy_input = np.random.rand(*shape).astype(dtype)

print("Input name:", input_name)
print("Input shape:", shape)

# Warm-up
for _ in range(10):
    session.run(None, {input_name: dummy_input})

# Benchmark
runs = 100
start = time.perf_counter()

for _ in range(runs):
    session.run(None, {input_name: dummy_input})

elapsed = time.perf_counter() - start

print(f"DirectML inference speed: {runs / elapsed:.2f} inferences/second")
print(f"Average inference time: {elapsed / runs * 1000:.2f} ms")