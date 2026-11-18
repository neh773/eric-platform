"""SCR-037: docs/scripting-overview.md must list the same system namespaces as the code."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PY = (ROOT / "gateway/scripting/src/main/resources/oip_system.py").read_text()
DOC = (ROOT / "docs/scripting-overview.md").read_text()
HOST = (ROOT / "gateway/scripting/src/main/kotlin/org/oip/scripting/ScriptHost.kt").read_text()


def namespaces(text):
    out = []
    for n in re.findall(r'"system\.([a-z]+)\.', text):
        if n not in out:
            out.append(n)
    return out


def hints_block():
    i = PY.index("def scopeHints")
    return PY[i:PY.index("util = types.SimpleNamespace", i)]


def doc_list(prefix):
    m = re.search(r"^" + re.escape(prefix) + r":? (.*)\.$", DOC, re.M)
    assert m, prefix
    return [x.strip() for x in m.group(1).split(",")]


class ScriptingOverviewTest(unittest.TestCase):
    def test_built_in_namespaces_match_the_builder(self):
        tail = PY[PY.index("return types.SimpleNamespace(\n        tag=tag"):]
        built = []
        for n in re.findall(r"(\w+)=", tail.split(")\n")[0]):
            if n not in built:
                built.append(n)
        self.assertEqual(sorted(doc_list("Namespaces built into every script")), sorted(built))

    def test_scope_namespaces_match_scope_hints(self):
        b = hints_block()
        shared = namespaces(b[b.index("shared = ["):b.index("gateway = shared")])
        gateway = shared + [n for n in namespaces(b[b.index("gateway = shared"):b.index('if name == "session"')]) if n not in shared]
        session = shared + [n for n in namespaces(b[b.index('if name == "session"'):]) if n not in shared]
        self.assertEqual(sorted(doc_list("Namespaces with functions available in the gateway scope")), sorted(gateway))
        self.assertEqual(sorted(doc_list("Namespaces with functions available in the designer scope")), sorted(gateway))
        self.assertEqual(sorted(doc_list("Namespaces with functions available in the session scope")), sorted(session))

    def test_gateway_only_namespaces_match_library_access(self):
        gw = set()
        for fn, acc in re.findall(r'"([a-z]+\.[A-Za-z]+)" to LibraryAccess\.(GATEWAY|SELF|ANY)', HOST):
            if acc == "GATEWAY":
                gw.add(fn.split(".")[0])
        listed = set(doc_list("Functions in these namespaces refuse to run for a signed-in user's session"))
        # Namespaces gated through other host hooks (not LIBRARY_ACCESS) are listed too; LIBRARY_ACCESS ones must all appear.
        self.assertTrue(gw <= listed, gw - listed)


if __name__ == "__main__":
    unittest.main()
