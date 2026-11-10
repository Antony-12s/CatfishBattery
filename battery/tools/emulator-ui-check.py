"""Own a read-only Android 11 emulator for the entire release-APK visual sweep; never touch the car.
Usage: python3 battery/tools/emulator-ui-check.py APK OUTPUT_DIRECTORY
"""
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SDK = Path.home() / 'Android/Sdk'
ADB = SDK / 'platform-tools/adb'
SERIAL = 'emulator-5580'
PACKAGE = 'com.catfish.battery'
output = Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)


def adb(*args, timeout=30):
    return subprocess.run([str(ADB), '-s', SERIAL, *args], capture_output=True, check=True, timeout=timeout).stdout


def nodes():
    raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', timeout=45).decode()
    text = raw[raw.index('<?xml'):]
    text = text[:text.index('</hierarchy>') + len('</hierarchy>')]
    return ET.fromstring(text).iter('node')


def tap(*, resource=None, text=None):
    for node in nodes():
        if (resource and node.get('resource-id') == PACKAGE + ':id/' + resource) or (text and node.get('text') == text):
            left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds', '')))
            if right > left and bottom > top:
                adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
                time.sleep(1)
                return
    raise AssertionError('Control not found: ' + str(resource or text))


def screenshot(name):
    path = output / (name + '.png')
    path.write_bytes(adb('exec-out', 'screencap', '-p'))
    print(path, flush=True)


# Fail before starting or cleaning up anything if another session owns this transport/port.
devices = subprocess.check_output([str(ADB), 'devices'], text=True)
if SERIAL in devices:
    raise RuntimeError('Emulator transport already in use: ' + SERIAL)
for port in (5580, 5581):
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))

log = (output / 'emulator.log').open('w')
process = subprocess.Popen([str(SDK / 'emulator/emulator'), '-avd', 'geely_a11', '-port', '5580',
                            '-read-only', '-no-snapshot', '-no-window', '-no-audio', '-no-boot-anim',
                            '-gpu', 'swiftshader', '-memory', '2048'], stdout=log, stderr=subprocess.STDOUT)
try:
    deadline = time.monotonic() + 160
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Owned emulator exited: ' + str(process.returncode))
        try:
            if adb('shell', 'getprop', 'sys.boot_completed', timeout=5).strip() == b'1':
                break
        except (subprocess.SubprocessError, OSError):
            pass
        time.sleep(2)
    else:
        raise TimeoutError('Owned emulator boot timed out')
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1'
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    # This disposable read-only AVD inherited developer display overlays; they can intercept test taps.
    adb('shell', 'settings', 'delete', 'global', 'overlay_display_devices')
    time.sleep(2)
    adb('install', '-r', str(Path(sys.argv[1]).resolve()), timeout=90)
    adb('shell', 'logcat', '-c')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.BatteryActivity')
    time.sleep(10)
    coverage = []
    for theme, mode in [('day', 'no'), ('night', 'yes')]:
        adb('shell', 'cmd', 'uimode', 'night', mode)
        time.sleep(4)
        for page in ['status', 'charging', 'history', 'settings']:
            tap(resource='battery_tab_' + page)
            time.sleep(2)
            if page == 'status':
                tap(text='Schematic')
                screenshot('status-schematic-' + theme)
                tap(text='Cells')
                screenshot('status-cells-' + theme)
                tap(text='Schematic')
            else:
                screenshot(page + '-' + theme)
            coverage.append(theme + '/' + page)
    # Saves the unchanged default settings on this throwaway emulator, never on the car.
    tap(text='Save')
    time.sleep(2)
    assert any(node.get('text') == 'Saved' for node in nodes()), 'Save did not show Saved'
    tap(resource='battery_tab_status')
    tap(resource='battery_tab_settings')
    assert any(node.get('text') == 'Saved' for node in nodes()), 'Saved settings did not survive navigation'
    logs = adb('logcat', '-d').decode(errors='replace')
    (output / 'logcat.txt').write_text(logs)
    assert 'FATAL EXCEPTION' not in logs, 'Runtime crash; inspect logcat.txt'
    (output / 'coverage.json').write_text(json.dumps({'coverage': coverage, 'saveRoundTrip': True, 'crashes': False}, indent=2))
    print('PASS: actual release APK, 4 tabs x day/night; Schematic/Cells; Save and reopen; no runtime crash.', flush=True)
except Exception:
    try:
        screenshot('FAILED-state')
        (output / 'failed-logcat.txt').write_bytes(adb('logcat', '-d'))
    except (subprocess.SubprocessError, OSError):
        pass
    raise
finally:
    try:
        adb('emu', 'kill', timeout=8)
    except (subprocess.SubprocessError, OSError):
        process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
    log.close()
