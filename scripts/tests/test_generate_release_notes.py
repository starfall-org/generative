"""Offline checks for the shared GitHub release-note generator."""
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "generate-release-notes.py"
spec = importlib.util.spec_from_file_location("generate_release_notes", SCRIPT)
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


class ReleaseNotesTest(unittest.TestCase):
    def test_previous_tag_uses_its_own_release_stream(self):
        tags = "\n".join([
            "1.2.799", "1.2.790", "1.2.799-unstable",
            "1.2.760-unstable", "1.2.799-multigateway",
            "1.2.800", "1.2.800-unstable", "v0.1.1",
        ])
        with patch.object(notes, "git", return_value=tags):
            self.assertEqual("1.2.799", notes.previous_release_tag("1.2.800"))
            self.assertEqual("1.2.799-unstable",
                             notes.previous_release_tag("1.2.800-unstable"))
            self.assertIsNone(notes.previous_release_tag("1.2.700-feature-x"))

    def test_collect_changes_keeps_first_commit_and_ignores_internal_entries(self):
        sample = (
            "\x1eabc\x1ffix: prevent accidental folder exits\x1f"
            "Affects provider drag gestures\x1f\n"
            "app/src/main/kotlin/org/example/ProvidersScreen.kt\n"
            "\x1edef\x1fdocs: update website\x1f\x1f\nwebsite/index.html\n"
            "\x1eghi\x1ftest: add device tests\x1f\x1f\n"
            "app/src/androidTest/java/org/example/DragTest.kt\n"
            "\x1ejkl\x1fUpdate README\x1f\x1f\nREADME.md\n"
        )
        with patch.object(notes, "git", return_value=sample):
            changes = notes.collect_changes("HEAD", "1.2.749")
        self.assertEqual(1, len(changes))
        self.assertEqual("prevent accidental folder exits", changes[0]["title"])
        self.assertEqual("Affects provider drag gestures", changes[0]["details"])
        self.assertEqual(["app/src/main/kotlin/org/example/ProvidersScreen.kt"], changes[0]["files"])

    def test_parse_ai_sections_drops_generic_repetitions_and_internal_claims(self):
        response = json.dumps({
            "highlights": [
                "Rearrange items inside folders without accidentally moving them outside.",
                "Refined UI",
                "Rearrange items inside folders without accidentally moving them outside.",
            ],
            "improvements": [
                "Speed up switching between providers and editing their settings.",
                "Android tests and Gradle tooling were improved.",
            ],
            "fixes": ["Fixed the folder exit boundary during drag and drop."],
        })
        sections = notes.parse_ai_sections("\x60" * 3 + "json\n" + response + "\n" + "\x60" * 3)
        self.assertEqual(1, len(sections["highlights"]))
        self.assertEqual(1, len(sections["improvements"]))
        self.assertEqual(1, len(sections["fixes"]))
        self.assertEqual("Rearrange items inside folders without accidentally moving them outside.",
                         sections["highlights"][0])

    def test_fallback_uses_concrete_changes_not_marketing_filler(self):
        cases = [
            {"kind": "fix", "title": "narrow provider folder side exit zones to 5dp"},
            {"kind": "feat", "title": "improve optimistic persistence and update Android workflows"},
            {"kind": "other", "title": "Update app UI, integrations, diagnostics and regression tests"},
            {"kind": "feat", "title": "add CLI account providers and improve model, sheet, and release support"},
        ]
        sections = notes.fallback_sections(cases)
        self.assertEqual(["Narrow provider folder side exit zones to 5dp."], sections["fixes"])
        self.assertTrue(any("reordered items immediately" in item
                            for item in sections["highlights"]))
        self.assertTrue(any("CLI-based" in item for item in sections["highlights"]))
        self.assertFalse(sections["improvements"])

    def test_render_omits_empty_categories_and_adds_changelog(self):
        markdown = notes.render_sections(
            {"highlights": ["Add a model provider."], "improvements": [],
             "fixes": ["Avoid accidental folder exits."]},
            "starfall-org/generative", "1.2.800", "1.2.799",
        )
        self.assertIn("### New & Notable", markdown)
        self.assertIn("### Fixes", markdown)
        self.assertNotIn("### Improvements", markdown)
        self.assertIn("/compare/1.2.799...1.2.800", markdown)

    def test_manual_notes_override_does_not_call_git_or_network(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "notes.md"
            approved = Path(directory) / "approved.md"
            approved.write_text("### New & Notable\n\n- A reviewed note.\n", encoding="utf-8")
            args = ["script", "--tag", "1.2.800", "--output", str(output)]
            with patch.dict(os.environ, {"RELEASE_NOTES_FILE": str(approved)}), \
                    patch.object(sys, "argv", args), \
                    patch.object(notes, "git", side_effect=AssertionError("git called")), \
                    patch.object(notes, "urlopen", side_effect=AssertionError("API called")):
                notes.main()
            self.assertEqual(approved.read_text(), output.read_text())

    def test_ai_request_can_parse_openai_chat_response(self):
        response = {"choices": [{"message": {"content": json.dumps({
            "highlights": ["Reorder provider entries without waiting for the database."],
            "improvements": [], "fixes": []
        })}}]}
        with patch.dict(os.environ, {
            "RELEASE_NOTES_API_BASE_URL": "https://example.invalid/v1",
            "RELEASE_NOTES_API_KEY": "secret-for-test",
            "RELEASE_NOTES_MODEL": "test-model",
        }), patch.object(notes, "urlopen", return_value=io.BytesIO(
            json.dumps(response).encode("utf-8")
        )) as request:
            result = notes.ai_sections(
                [{"kind": "feat", "title": "reorder provider entries immediately", "details": "",
                  "files": ["app/src/main/ProvidersScreen.kt"]}], "1.2.800"
            )
        self.assertEqual(1, len(result["highlights"]))
        sent = request.call_args.args[0]
        self.assertEqual("https://example.invalid/v1/chat/completions", sent.full_url)
        self.assertEqual("Bearer secret-for-test", sent.get_header("Authorization"))
        self.assertEqual("test-model", json.loads(sent.data)["model"])


if __name__ == "__main__":
    unittest.main()
