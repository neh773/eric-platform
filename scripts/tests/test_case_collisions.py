"""Case-only paths that a Windows checkout or a bundler would mix up."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import check_case_collisions as check  # noqa: E402


class CaseCollisionsTest(unittest.TestCase):
    def test_module_stems_that_differ_only_by_case(self):
        found = check.collisions([
            "web/designer/src/palette.ts",
            "web/designer/src/Palette.tsx",
        ])
        self.assertEqual(len(found), 1)
        self.assertIn("palette.ts", found[0])
        self.assertIn("Palette.tsx", found[0])

    def test_directory_spelling(self):
        found = check.collisions(["Foo/a.txt", "foo/b.txt"])
        self.assertEqual(len(found), 1)

    def test_same_stem_and_same_case_is_not_a_case_collision(self):
        self.assertEqual(check.collisions(["src/foo.ts", "src/foo.tsx"]), [])

    def test_renamed_palette_data_does_not_collide(self):
        self.assertEqual(check.collisions([
            "web/designer/src/paletteData.ts",
            "web/designer/src/Palette.tsx",
            "web/designer/src/palette.test.tsx",
        ]), [])


if __name__ == "__main__":
    unittest.main()
