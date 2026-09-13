import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("lua_markdown", Path(__file__).with_name("generate_markdown.py"))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class ReadOnlyMarkdownCheckTest(unittest.TestCase):
    def test_check_does_not_create_missing_output_or_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relative = Path("missing/README.md")
            with patch.object(generator, "build_artifacts", return_value={relative: "expected\n"}):
                with self.assertRaises(generator.MarkdownGenerationError):
                    generator.write_or_check(root, True)
            self.assertFalse((root / "missing").exists())

    def test_check_preserves_stale_file_and_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / "README.md"
            file.write_text("stale\n", encoding="utf-8")
            stamp = file.stat().st_mtime_ns
            with patch.object(generator, "build_artifacts", return_value={Path("README.md"): "expected\n"}):
                with self.assertRaises(generator.MarkdownGenerationError):
                    generator.write_or_check(root, True)
            self.assertEqual("stale\n", file.read_text(encoding="utf-8"))
            self.assertEqual(stamp, file.stat().st_mtime_ns)

    def test_write_then_check_accepts_matching_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(generator, "build_artifacts", return_value={Path("README.md"): "expected\n"}):
                self.assertEqual(1, generator.write_or_check(root, False))
                self.assertEqual(1, generator.write_or_check(root, True))


if __name__ == "__main__":
    unittest.main()
