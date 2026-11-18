"""Tests for scripts/gen_differences_index.py (no gateway, no network)."""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import gen_differences_index as gen  # noqa: E402

OLD = """# T

## Index

| # | Difference |
|---|---|
| [1](#d-1) | First lead. |

## Entries

1. <a id="d-1"></a>**First lead.** Body.

More of entry one.

2. <a id="d-2"></a>**Second lead.** Body.
"""


class GenDifferencesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name) / "differences"
        self.dir.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        (self.dir / name).write_text(text)

    def test_reads_entries_and_renders_them_in_number_order(self):
        self.write("0010.md", '10. <a id="d-10"></a>**Tenth.** Body.\n')
        self.write("0002.md", '2. <a id="d-2"></a>**Second | piped.** Body.\n')
        entries, errors = gen.read_entries(self.dir)
        self.assertEqual(errors, [])
        page = gen.render(entries)
        self.assertLess(page.index("[2](#d-2)"), page.index("[10](#d-10)"))
        self.assertIn("| [2](#d-2) | Second \\| piped. |", page)
        self.assertLess(page.index('2. <a id="d-2">'), page.index('10. <a id="d-10">'))

    def test_bad_files_are_errors(self):
        self.write("0003.md", '4. <a id="d-4"></a>**Wrong file.** Body.\n')
        self.write("0005.md", '5. <a id="d-6"></a>**Wrong id.** Body.\n')
        self.write("0007.md", '7. <a id="d-7"></a>**One.** x\n\n8. <a id="d-8"></a>**Two.** y\n')
        self.write("0009.md", '<<<<<<< ours\n9. <a id="d-9"></a>**Nine.** x\n=======\n>>>>>>> theirs\n')
        self.write("12.md", '12. <a id="d-12"></a>**Short name.** x\n')
        _, errors = gen.read_entries(self.dir)
        joined = "\n".join(errors)
        for needle in ("0003.md: holds entry 4", "0005.md: entry 5 has id d-6", "0007.md: holds exactly one entry",
                       "0009.md: merge conflict markers", "12.md: entry files are named NNNN.md"):
            self.assertIn(needle, joined)

    def test_import_writes_only_what_differs_from_the_base(self):
        old = pathlib.Path(self.tmp.name) / "old.md"
        base = pathlib.Path(self.tmp.name) / "base.md"
        old.write_text(OLD + '\n3. <a id="d-3"></a>**Third, new on the branch.** Body.\n')
        base.write_text(OLD.replace("**Second lead.** Body.", "**Second lead.** Older body."))
        written = gen.import_old(old, base, self.dir)
        self.assertEqual(written, [2, 3])
        self.assertFalse((self.dir / "0001.md").exists())
        entries, errors = gen.read_entries(self.dir)
        self.assertEqual(errors, [])
        self.assertEqual(sorted(entries), [2, 3])

    def test_import_without_a_base_keeps_multi_paragraph_entries_whole(self):
        old = pathlib.Path(self.tmp.name) / "old.md"
        old.write_text(OLD)
        gen.import_old(old, None, self.dir)
        self.assertEqual((self.dir / "0001.md").read_text(), '1. <a id="d-1"></a>**First lead.** Body.\n\nMore of entry one.\n')


if __name__ == "__main__":
    unittest.main()
