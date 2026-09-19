import threading
import time
import os

import poultrysense_ai as appmod


def main(port=5050):
    # Open camera on the main thread first (required for macOS camera permission).
    cap = appmod.open_camera()
    cam_thread = threading.Thread(
        target=appmod.camera_loop,
        kwargs={"cap": cap},
        daemon=True,
    )
    cam_thread.start()
    time.sleep(0.3)
    print(f"[INFO] Launching PoultrySense AI on http://0.0.0.0:{port}")
    appmod.flask_app.run(host='0.0.0.0', port=port, debug=False, threaded=True)


if __name__ == '__main__':
    # 5050 avoids macOS AirPlay Receiver on port 5000
    port = int(os.environ.get('POULTRYSENSE_PORT', '5050'))
    main(port=port)
