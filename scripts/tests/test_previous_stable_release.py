"""Select stable GitHub Release baselines independently of prerelease tags."""
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "previous-stable-release.py"
spec = importlib.util.spec_from_file_location("previous_stable_release", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StableReleaseBaselineTest(unittest.TestCase):
    def test_ignores_prereleases_and_uses_last_stable(self):
        tags = ["1.2.812-unstable", "1.2.776", "1.2.770-unstable",
                "1.2.749", "1.2.741-unstable"]
        self.assertEqual("1.2.776", module.previous_stable_release("1.2.813", tags))

    def test_excludes_current_tag_when_republishing(self):
        tags = ["1.2.776", "1.2.749", "1.2.748"]
        self.assertEqual("1.2.749", module.previous_stable_release("1.2.776", tags))

    def test_version_order_not_release_list_order(self):
        tags = ["v1.9.99", "v1.10.1", "v1.10.9", "v1.11.1"]
        self.assertEqual("v1.10.9", module.previous_stable_release("1.11.0", tags))

    def test_no_earlier_stable(self):
        self.assertIsNone(module.previous_stable_release(
            "1.2.776", ["1.2.776", "1.2.777", "1.2.775-unstable"]))

    def test_rejects_prerelease_target(self):
        with self.assertRaises(ValueError):
            module.previous_stable_release("1.2.813-unstable", ["1.2.776"])


if __name__ == "__main__":
    unittest.main()
