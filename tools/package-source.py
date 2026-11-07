#!/usr/bin/env python3
"""Package the actual app-source tree, never credentials or generated build output."""
from __future__ import annotations
import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app-source'
SKIP_DIRS = {'node_modules', '.gradle', '.git', '__pycache__', 'artifacts'}
SKIP_PREFIXES = ('android/app/build/', 'android/app/src/main/assets/', 'build/')
SKIP_FILES = {'android/local-debug.keystore', 'android/app/src/main/res/drawable/vishal_jarvis_icon.png'}

def include(path: Path) -> bool:
    rel = path.relative_to(SOURCE).as_posix()
    return (path.is_file() and not any(part in SKIP_DIRS for part in path.relative_to(SOURCE).parts)
            and not rel.startswith(SKIP_PREFIXES) and rel not in SKIP_FILES
            and not rel.endswith(('.apk', '.keystore', '.jks', '.log')))

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
