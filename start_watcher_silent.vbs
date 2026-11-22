Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "C:\Gamitani\PoultrySense-Andriod-Studio--gamita-ni"
WshShell.Run """C:\Gamitani\PoultrySense-Andriod-Studio--gamita-ni\poultrysense_ai\venv\Scripts\pythonw.exe"" ""C:\Gamitani\PoultrySense-Andriod-Studio--gamita-ni\poultrysense_watcher.py""", 0, False
