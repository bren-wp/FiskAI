"""Capture actual debug APK UI on a disposable CI emulator. No customer data."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "hr.brendigo.fiskai"
OUT = pathlib.Path("screenshots/actual")
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, binary=False):
    return subprocess.check_output(["adb", *args], timeout=30, text=not binary)

def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/fiskai-capture.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/fiskai-capture.xml"))

def find(label):
    for _ in range(12):
        try:
            root = tree()
            for node in root.iter("node"):
                if label in (node.get("text"), node.get("content-desc")):
                    bounds = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
                    if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                        return bounds
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError("UI element unavailable: " + label)

def tap(label):
    a,b,c,d = find(label)
    adb("shell", "input", "tap", str((a+c)//2), str((b+d)//2))

def capture(name, label):
    find(label)
    time.sleep(0.5)
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png)<1000:
        raise RuntimeError("Invalid screenshot")
    (OUT / (name + ".png")).write_bytes(png)

if __name__ == "__main__":
    adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
    # This script runs only in an isolated CI emulator.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    capture("01-pocetna", "Pregled poslovanja")
    tap("Računi")
    capture("02-racuni", "Pretraži nacrte")
    tap("Više")
    capture("03-vise", "Poslovni subjekt")
    tap("Proizvodi i usluge")
    capture("04-katalog", "+ Dodaj proizvod ili uslugu")
    adb("shell", "rm", "-f", "/sdcard/fiskai-capture.xml")
