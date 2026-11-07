#!/usr/bin/env python3
"""Package the actual app-source tree, never credentials or generated build output."""
from __future__ import annotations
import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app-source'
SKIP_DIRS = {'node_modules', '.gradle', '.git', '__pycache__', 'artifacts'}
SKIP_PREFIXES = ('android/build/', 'android/app/build/', 'android/app/src/main/assets/', 'build/')
SKIP_FILES = {'android/local.properties', 'android/keystore.properties',
              'android/app/google-services.json',
              'android/app/src/main/res/drawable/vishal_jarvis_icon.png'}
SKIP_SUFFIXES = ('.apk', '.aab', '.keystore', '.jks', '.p12', '.pfx', '.pem', '.key', '.log')

def include(path: Path) -> bool:
    relpath = path.relative_to(SOURCE)
    rel = relpath.as_posix()
    name = path.name.lower()
    return (path.is_file() and not path.is_symlink() and not any(part in SKIP_DIRS for part in relpath.parts)
            and not rel.startswith(SKIP_PREFIXES) and rel not in SKIP_FILES
            and not name.startswith('.env') and not name.endswith(SKIP_SUFFIXES))

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default=str(ROOT / 'Vishal-Jarvis-Android-App-Source.zip'))
    args = parser.parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    files = sorted(path for path in SOURCE.rglob('*') if include(path))
    with ZipFile(output, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            info = ZipInfo('app-source/' + path.relative_to(SOURCE).as_posix(), (2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = (0o100644 << 16)
            archive.writestr(info, path.read_bytes(), compress_type=ZIP_DEFLATED, compresslevel=9)
    print(f'Packaged {len(files)} existing application source files in {output}')

if __name__ == '__main__':
    main()
