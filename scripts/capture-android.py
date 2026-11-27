"""Capture actual debug APK UI on a disposable CI emulator. No customer data."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "hr.brendigo.fiskai"
OUT = pathlib.Path("screenshots/actual")
DIAGNOSTICS = pathlib.Path("build/capture-diagnostics")

def adb(*args, binary=False):
    return subprocess.check_output(["adb", *args], timeout=30, text=not binary)

def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/fiskai-capture.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/fiskai-capture.xml"))

def normalized(value):
    # Compose and UIAutomator may differ in whitespace and line wrapping.
    # Preserve case, punctuation and all words; do not use substring matching.
    return " ".join((value or "").split())

def bounds_for(root, label):
    expected = normalized(label)
    if not expected:
        raise ValueError("Empty UI label")
    for node in root.iter("node"):
        if expected in (normalized(node.get("text")), normalized(node.get("content-desc"))):
            raw = node.get("bounds", "")
            match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", raw)
            if match:
                a, b, c, d = map(int, match.groups())
                if a >= 0 and b >= 0 and c > a and d > b:
                    return [a, b, c, d]
    return None

def find(label):
    for _ in range(12):
        try:
            bounds = bounds_for(tree(), label)
            if bounds is not None:
                return bounds
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError("UI element unavailable: " + label)

def tap(label):
    a,b,c,d = find(label)
    adb("shell", "input", "tap", str((a+c)//2), str((b+d)//2))

def capture(name, *labels):
    for label in labels:
        find(label)
    time.sleep(0.5)
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png)<1000:
        raise RuntimeError("Invalid screenshot")
    (OUT / (name + ".png")).write_bytes(png)

def save_diagnostics():
    DIAGNOSTICS.mkdir(parents=True, exist_ok=True)
    # This runs only on a disposable test emulator, never on a customer device.
    for name, action in (
        ("failure.png", lambda: adb("exec-out", "screencap", "-p", binary=True)),
        ("hierarchy.xml", lambda: ET.tostring(tree(), encoding="utf-8")),
        ("activity.txt", lambda: adb("shell", "dumpsys", "activity", "activities").encode("utf-8")),
    ):
        try:
            (DIAGNOSTICS / name).write_bytes(action())
        except Exception as exc:
            print("Capture diagnostic unavailable:", name, type(exc).__name__)

def main():
    OUT.mkdir(parents=True, exist_ok=True)
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
    capture("04-katalog", "Svi artikli", "Filtri", "+ Dodaj proizvod ili uslugu")
    # Verify the actual add action, not merely a visible button label.
    tap("+ Dodaj proizvod ili uslugu")
    capture("05-novi-artikl", "Artikl", "Naziv", "Spremi")
    tap("Odustani")
    find("Svi artikli")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        save_diagnostics()
        raise
    finally:
        try:
            adb("shell", "rm", "-f", "/sdcard/fiskai-capture.xml")
        except subprocess.SubprocessError:
            pass
