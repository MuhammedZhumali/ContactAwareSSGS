import json
from pathlib import Path
import sys
import tempfile
import unittest
import run_security_evaluation_se21 as runner

class WatchdogTest(unittest.TestCase):
    def test_hung_owned_process_is_killed_without_losing_checkpoint(self):
        with tempfile.TemporaryDirectory(dir=runner.ROOT / 'target') as tmp:
            path = Path(tmp)
            script = "import pathlib,time;pathlib.Path('checkpoint.json').write_text('{\"generation\":7}');time.sleep(30)"
            result = runner.child([sys.executable, '-c', script], 1, path / 'process.log', path)
            self.assertEqual(result['processStatus'], 'PROCESS_TIMEOUT')
            self.assertEqual(json.loads((path / 'checkpoint.json').read_text()), {'generation': 7})

    def test_exit_error_is_not_reported_as_timeout_or_complete(self):
        with tempfile.TemporaryDirectory(dir=runner.ROOT / 'target') as tmp:
            result = runner.child([sys.executable, '-c', 'raise SystemExit(3)'], 5, Path(tmp) / 'process.log')
            self.assertEqual(result['processStatus'], 'PROCESS_ERROR')
            self.assertEqual(result['exitCode'], 3)

if __name__ == '__main__':
    unittest.main()
