"""Tests for check-baselines.py (ADR-0049 decision 3). Run: python3 -I -m unittest discover -s scripts -p 'test_*.py'

Each test builds a throwaway git repository: a base commit (the ref `base`) and a staged change, which is what the
check reads. The cases that add an entry are the proof that the check fails when a baseline grows.
"""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("check_baselines", HERE / "check-baselines.py")
cb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cb)


def lint_xml(*issues):
    """issues: (id, message, line). The line is in the file but is not part of an entry."""
    body = "".join(
        f'\n    <issue\n        id="{i}"\n        message="{m}"\n        errorLine1="x = 1">\n'
        f'        <location\n            file="src/main/A.kt"\n            line="{n}"\n            column="1"/>\n    </issue>\n'
        for i, m, n in issues)
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" by="lint 9.4.1" type="baseline">\n{body}\n</issues>\n'


def detekt_xml(*ids):
    body = "".join(f"    <ID>{i}</ID>\n" for i in ids)
    return f"<?xml version=\"1.0\" ?>\n<SmellBaseline>\n  <ManuallySuppressedIssues/>\n  <CurrentIssues>\n{body}  </CurrentIssues>\n</SmellBaseline>\n"


class BaselineCheck(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def write(self, path, text):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        self.git("add", path)

    def base(self, files):
        for path, text in files.items():
            self.write(path, text)
        self.git("commit", "-q", "-m", "base")
        self.git("branch", "base")

    def errors(self):
        return cb.check("base", cwd=self.root)

    LINT = lint_xml(("A", "first", 10), ("B", "second", 20))
    DETEKT = detekt_xml("MagicNumber:A.kt:8", "ReturnCount:A.kt:A$fun f")

    def both(self):
        self.base({"m/lint-baseline.xml": self.LINT, "m/detekt-baseline.xml": self.DETEKT})

    def test_unchanged_passes(self):
        self.both()
        self.assertEqual([], self.errors())

    def test_removing_entries_passes(self):
        self.both()
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10)))
        self.write("m/detekt-baseline.xml", detekt_xml())
        self.assertEqual([], self.errors())

    def test_lint_line_numbers_are_not_part_of_an_entry(self):
        self.both()
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 99), ("B", "second", 5)))
        self.assertEqual([], self.errors())

    def test_added_detekt_entry_fails(self):
        self.both()
        self.write("m/detekt-baseline.xml", detekt_xml("MagicNumber:A.kt:8", "ReturnCount:A.kt:A$fun f", "LongMethod:B.kt:B$fun g"))
        errs = self.errors()
        self.assertEqual(1, len(errs))
        self.assertIn("m/detekt-baseline.xml has 1 entries of LongMethod in B.kt", errs[0])

    def test_added_lint_entry_fails(self):
        self.both()
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10), ("B", "second", 20), ("C", "third", 30)))
        errs = self.errors()
        self.assertEqual(1, len(errs))
        self.assertIn("m/lint-baseline.xml has 1 entries of C in src/main/A.kt", errs[0])

    def test_reworded_lint_message_is_not_a_new_entry(self):
        # A tool update can reword a message; the entry stays the same rule in the same file.
        self.both()
        self.write("m/lint-baseline.xml", lint_xml(("A", "first, new wording", 10), ("B", "second", 20)))
        self.assertEqual([], self.errors())

    def test_renamed_declaration_is_not_a_new_detekt_entry(self):
        self.both()
        self.write("m/detekt-baseline.xml", detekt_xml("MagicNumber:A.kt:9", "ReturnCount:A.kt:A$fun g"))
        self.assertEqual([], self.errors())

    def test_one_more_detekt_entry_of_a_rule_in_a_file_fails(self):
        self.both()
        self.write("m/detekt-baseline.xml", detekt_xml("MagicNumber:A.kt:8", "MagicNumber:A.kt:9", "ReturnCount:A.kt:A$fun f"))
        errs = self.errors()
        self.assertEqual(1, len(errs))
        self.assertIn("MagicNumber in A.kt", errs[0])

    def test_same_rule_in_another_file_fails(self):
        self.both()
        self.write("m/detekt-baseline.xml", detekt_xml("MagicNumber:A.kt:8", "MagicNumber:B.kt:8", "ReturnCount:A.kt:A$fun f"))
        self.assertEqual(1, len(self.errors()))

    def test_a_second_copy_of_an_entry_fails(self):
        self.both()
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10), ("A", "first", 11), ("B", "second", 20)))
        self.assertEqual(1, len(self.errors()))

    def test_new_baseline_file_with_entries_fails(self):
        self.both()
        self.write("n/detekt-baseline.xml", detekt_xml("MagicNumber:C.kt:1"))
        self.assertEqual(1, len(self.errors()))

    def test_new_empty_baseline_file_passes(self):
        self.both()
        self.write("n/detekt-baseline.xml", detekt_xml())
        self.write("n/lint-baseline.xml", lint_xml())
        self.assertEqual([], self.errors())

    def test_kind_missing_on_base_is_skipped(self):
        # The PR that adds the first baselines: main has none of that kind.
        self.base({"README.md": "x\n"})
        self.write("m/detekt-baseline.xml", detekt_xml("MagicNumber:A.kt:8"))
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10)))
        self.assertEqual([], self.errors())

    def test_other_kind_is_still_checked_once_one_kind_is_on_base(self):
        self.base({"m/detekt-baseline.xml": self.DETEKT})
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10)))  # lint: skipped (none on base)
        self.write("m/detekt-baseline.xml", detekt_xml("MagicNumber:A.kt:8", "ReturnCount:A.kt:A$fun f", "X:Y.kt:1"))
        self.assertEqual(1, len(self.errors()))

    def test_a_main_that_moved_on_does_not_count_against_the_branch(self):
        # `base` loses entry B after the branch point. The branch did not touch the baseline: no error.
        self.base({"m/lint-baseline.xml": self.LINT})
        self.git("checkout", "-q", "-b", "feat")
        self.git("checkout", "-q", "base")
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10)))
        self.git("commit", "-q", "-m", "main fixes B")
        self.git("checkout", "-q", "feat")
        self.assertEqual([], self.errors())
        # A real addition on the branch still fails.
        self.write("m/lint-baseline.xml", lint_xml(("A", "first", 10), ("B", "second", 20), ("C", "third", 30)))
        self.assertEqual(1, len(self.errors()))

    def test_missing_base_ref_fails(self):
        self.both()
        self.assertEqual(1, len(cb.check("no-such-ref", cwd=self.root)))


if __name__ == "__main__":
    unittest.main()
