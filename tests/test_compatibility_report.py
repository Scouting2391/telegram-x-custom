import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('build_custom_report', ROOT / 'scripts/build_custom.py')
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)


class CompatibilityReportTest(unittest.TestCase):
    def fixture(self):
        return {'smali/renamed.smali': '.class public final Lrenamed;\n.super Ljava/lang/Object;\n'
                '.method public b()V\n.locals 0\nconst-string v0, "requestUpdateInfo(%s)"\nreturn-void\n.end method',
                'smali/unrelated.smali': '.class public Lunrelated;\n.super Ljava/lang/Object;'}

    def test_failure_captures_public_context_without_allowing_publication(self):
        report = bc.compatibility_report(self.fixture(), 'v0.29.1.1818', 'a' * 64,
                                         error=ValueError('Unknown upstream updater shape'))
        self.assertEqual(report['status'], 'review-required')
        self.assertFalse(report['publicationAllowedByThisReport'])
        self.assertEqual(report['error'], 'ValueError: Unknown upstream updater shape')
        self.assertEqual(len(report['candidates']), 1)
        self.assertEqual(report['candidates'][0]['class'], 'Lrenamed;')
        self.assertIn('requestUpdateInfo(%s)', report['candidates'][0]['snippets'][0]['source'])
        self.assertEqual(report['changedMethods'], {})
        json.dumps(report)

    def test_recognized_patterns_are_not_a_build_or_runtime_success(self):
        files = self.fixture()
        report = bc.compatibility_report(files, 'v0.29.1.1818', 'b' * 64,
                                         {'smali/renamed.smali': 'changed'}, {'smali/renamed.smali': {'public b()V'}})
        self.assertEqual(report['status'], 'recognized')
        self.assertFalse(report['publicationAllowedByThisReport'])
        self.assertEqual(report['runtimeDeviceTest'], 'not performed')
        self.assertEqual(report['changedClassCount'], 1)
        self.assertEqual(report['changedMethods']['smali/renamed.smali'], ['public b()V'])

    def test_diagnostic_volume_is_bounded(self):
        source = self.fixture()['smali/renamed.smali'].replace('return-void', 'x' * 50000 + '\nreturn-void')
        files = {f'smali/c{i}.smali': source for i in range(30)}
        report = bc.compatibility_report(files, 'v0.29.1.1818', 'c' * 64, error=ValueError('e' * 10000))
        self.assertLessEqual(len(report['candidates']), 12)
        self.assertLessEqual(len(report['error']), 1500)
        self.assertTrue(all(len(s['source']) <= 12000 for c in report['candidates'] for s in c['snippets']))


if __name__ == '__main__':
    unittest.main()
