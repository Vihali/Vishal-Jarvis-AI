#!/usr/bin/env python3
"""Regression checks for a deterministic source ZIP without local build output or keys."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location('package_source', Path(__file__).with_name('package-source.py'))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class SourcePackageTests(unittest.TestCase):
    def test_excludes_generated_and_sensitive_files_and_is_reproducible(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'app-source'
            contents = {
                'README.md': 'source documentation',
                'src/mobile/main.ts': 'export const answer = 42;',
                'android/gradle.properties': 'org.gradle.jvmargs=-Xmx2g',
                'android/build/reports/problems/problems-report.html': 'generated',
                'android/app/build/outputs/apk/app.apk': 'generated',
                'android/app/src/main/assets/mobile.html': 'generated',
                'android/local.properties': 'local SDK path',
                'android/keystore.properties': 'password',
                'android/local-debug.keystore': 'key',
                'android/app/google-services.json': 'local configuration',
                'node_modules/dependency/index.js': 'dependency',
                '.env.production': 'API_KEY=local-secret',
                'secrets/private.P12': 'private key',
            }
            for name, data in contents.items():
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(data, encoding='utf-8')
            out1, out2 = root / 'one.zip', root / 'two.zip'
            with patch.object(package, 'SOURCE', source):
                for out in (out1, out2):
                    with patch.object(sys, 'argv', ['package-source.py', '--output', str(out)]):
                        package.main()
            self.assertEqual(out1.read_bytes(), out2.read_bytes())
            with ZipFile(out1) as archive:
                self.assertEqual(set(archive.namelist()), {
                    'app-source/README.md',
                    'app-source/src/mobile/main.ts',
                    'app-source/android/gradle.properties',
                })
                self.assertNotIn(b'local-secret', b''.join(archive.read(name) for name in archive.namelist()))


if __name__ == '__main__':
    unittest.main()
