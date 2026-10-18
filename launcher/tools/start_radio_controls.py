#!/usr/bin/env python3
"""Restart official Shizuku over USB after reboot (required on non-rooted Android 10)."""
import argparse
import pathlib
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Tablet serial from adb devices')
    parser.add_argument('--adb', default='adb', help='Path to Android platform-tools adb')
    args = parser.parse_args()

    def run(*command):
        return subprocess.check_output([args.adb, '-s', args.serial, *command], text=True, timeout=30).strip()

    paths = run('shell', 'pm', 'path', 'moe.shizuku.privileged.api').splitlines()
    base = next((path.removeprefix('package:') for path in paths if path.endswith('/base.apk')), None)
    if not base:
        parser.error('Install official Shizuku first: https://shizuku.rikka.app/download/')
    abi = run('shell', 'getprop', 'ro.product.cpu.abi')
    arch = {'arm64-v8a': 'arm64', 'armeabi-v7a': 'arm', 'x86_64': 'x86_64', 'x86': 'x86'}.get(abi)
    if not arch:
        parser.error('Unsupported device ABI; use View command in Shizuku.')
    starter = str(pathlib.PurePosixPath(base).parent / 'lib' / arch / 'libshizuku.so')
    print(run('shell', starter))
    print('Shizuku started. Open AniHome quick settings; its existing authorization is retained.')


if __name__ == '__main__':
    main()
