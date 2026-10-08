#!/usr/bin/env python3
"""Fixed-oracle stdlib tests; all document/CLI fixtures live in owned temp dirs.

Run: python3 -B scripts/test-check-docs.py
No real repository documentation, Git repository, network, or dependencies are
needed. Even the default-discovery CLI test uses a temporary fake Git command.
"""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().with_name("check-docs.py")
SPEC = importlib.util.spec_from_file_location("check_docs", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class DocumentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="check-docs-test-")
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).resolve()
        self.root = self.workspace / "repository"
        self.root.mkdir()
        self.cwd = self.workspace / "unrelated-cwd"
        self.cwd.mkdir()

    def put(self, name, content=""):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")
        return path

    def check(self, content, name="README.md", documents=None):
        self.put(name, content)
        return CHECKER.check_documents(self.root, documents or [name])

    def assert_clean(self, content, links, name="README.md"):
        issues, counts = self.check(content, name)
        self.assertEqual([], issues)
        self.assertEqual(links, counts["links"])
        self.assertEqual(0, counts["issues"])
        return counts

    def cli(self, *arguments, script=SCRIPT, env=None):
        environment = dict(os.environ, PATH="", PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8")
        if env:
            environment.update(env)
        return subprocess.run([sys.executable, "-B", str(script)] + list(arguments),
                              cwd=str(self.cwd), env=environment,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              encoding="utf-8", check=False)

    def test_relative_file_directory_and_anchor_only_links(self):
        self.put("guide.md", "# Guide\n")
        (self.root / "assets").mkdir()
        counts = self.assert_clean(
            "# Home\n[guide](../guide.md#guide)\n[directory](../assets/)\n"
            "[self](#home) [top](#) [empty]() [parent](../)\n",
            6, name="docs/index.md")
        self.assertEqual(dict(documents=1, skipped_documents=0, links=6,
                              local_links=6, external_links=0, issues=0), counts)

    def test_heading_slugs_have_fixed_unicode_number_and_code_oracles(self):
        self.put("headings.md",
                 "# 1. 快速开始（中文）\n"
                 "## **2.** `CloudSimTags.CLOUDLET_SUBMIT_ACK`\n"
                 "## Café &amp; Déjà Vu!\n"
                 "## _Italic_ __Bold__ snake_case `__CODE_ENUM__`\n"
                 "## Emoji 😄 / punctuation: A+B!\n"
                 "## [API Guide](https://example.invalid/) & `X_Y`\n"
                 "## A  B\n"
                 "## <em>Markup</em> and ~tilde~ ###\n"
                 "## `&amp;`\n"
                 "## E\u0301cole\n")
        self.assert_clean(
            "[a](headings.md#1-快速开始中文)\n"
            "[b](headings.md#2-cloudsimtagscloudlet_submit_ack)\n"
            "[c](headings.md#café--déjà-vu)\n"
            "[d](headings.md#italic-bold-snake_case-__code_enum__)\n"
            "[e](headings.md#emoji---punctuation-ab)\n"
            "[f](headings.md#api-guide--x_y)\n"
            "[g](headings.md#a--b)\n"
            "[h](headings.md#markup-and-tilde)\n"
            "[i](headings.md#amp)\n"
            "[j](headings.md#e\u0301cole)\n", 10)

    def test_duplicate_heading_suffixes_resolve_collisions(self):
        self.put("headings.md", "# Repeat\n## Repeat-1\n## Repeat\n## Repeat\n## Repeat-1\n")
        self.assert_clean(
            "[a](headings.md#repeat) [b](headings.md#repeat-1) "
            "[c](headings.md#repeat-2) [d](headings.md#repeat-3) "
            "[e](headings.md#repeat-1-1)\n", 5)
        issues, _ = self.check("[wrong](headings.md#repeat-4)\n")
        self.assertEqual(1, len(issues))
        self.assertIn("anchor '#repeat-4' does not exist", issues[0].reason)

    def test_setext_and_container_headings(self):
        self.put("headings.md", "Setext Title\n============\n\nSecond 标题\n---\n\n"
                 "> ## Quote\n\n- ### List Heading\n")
        self.assert_clean("[a](headings.md#setext-title) [b](headings.md#second-标题) "
                          "[c](headings.md#quote) [d](headings.md#list-heading)\n", 4)

    def test_explicit_html_ids_and_names_are_case_sensitive(self):
        self.put("anchors.md", '<a id="Custom-ID"></a>\n<a name=legacy></a>\n'
                 "<span id='中文&amp;ID'>text</span>\n<input id='self-closing'/>\n")
        self.put("page.html", '<html><a name="Section"></a><div id="Main"></div></html>')
        self.assert_clean(
            "[a](anchors.md#Custom-ID) [b](anchors.md#legacy) "
            "[c](anchors.md#中文%26ID) [d](anchors.md#self-closing) "
            "[e](page.html#Section) [f](page.html#Main)\n", 6)
        issues, _ = self.check("[wrong](anchors.md#custom-id)\n")
        self.assertEqual(1, len(issues))
        self.assertIn("does not exist", issues[0].reason)

    def test_titles_spaces_angles_nested_parentheses_and_escapes(self):
        self.put("Space File.md", "# Hello World\n")
        self.put("nested(a(b)).md", "# Nested\n")
        self.put("a(b).md", "# Escaped\n")
        self.put("中文.md", "# 标题\n")
        self.assert_clean(
            '[a](Space%20File.md#hello-world "double title")\n'
            "[b](<Space File.md#hello-world> 'single title')\n"
            '[c](nested(a(b)).md#nested (parenthesized title))\n'
            r'[d](a\(b\).md#escaped "title with \"quote\"")' + "\n"
            '[e](%E4%B8%AD%E6%96%87.md#%E6%A0%87%E9%A2%98)\n'
            '[f](Space%20File.md?view=1#hello-world)\n', 6)

    def test_fragment_separator_is_decoded_after_url_splitting(self):
        self.put("a#b.md", "# Literal Hash\n")
        self.put("%23.md", "# One Decode\n")
        self.put("a?b.md", "# Query Filename\n")
        self.assert_clean("[a](a%23b.md#literal-hash) [b](%2523.md#one-decode) "
                          "[c](a%3Fb.md#query-filename)\n", 3)
        issues, _ = self.check("[bad](a#b.md)\n")
        self.assertEqual("local target does not exist", issues[0].reason)

    def test_reference_links_images_and_first_definition_wins(self):
        self.put("Space File.md", "# Target\n")
        self.put("image.png", b"\x89PNG\x00\xff")
        self.assert_clean(
            "[full][  MIXED\tlabel ] [Collapsed][] [Shortcut]\n"
            "![image][picture] ![picture][] ![picture]\n"
            "[mixed label]: <Space File.md#target> \"title\"\n"
            "[MIXED LABEL]: missing.md\n"
            "[Collapsed]: Space%20File.md#target 'title'\n"
            "[Shortcut]: Space%20File.md#target\n"
            "[picture]: image.png\n"
            "[unused]: never-read.md\n", 6)

    def test_heading_reference_labels_not_destinations_form_slugs(self):
        self.put("headings.md", "# [Friendly Name][api]\n\n[api]: https://example.invalid/a\n")
        self.assert_clean("[a](headings.md#friendly-name)\n", 1)

    def test_linked_image_checks_both_targets(self):
        self.put("image.svg", "<svg/>")
        self.put("target.md", "# Target\n")
        self.assert_clean("[![badge](image.svg)](target.md#target)\n", 2)
        issues, counts = self.check("[![badge](missing.svg)](gone.md)\n")
        self.assertEqual(2, counts["links"])
        self.assertEqual({"missing.svg", "gone.md"}, {issue.target for issue in issues})

    def test_fences_inline_code_comments_and_indented_code_are_ignored(self):
        self.assert_clean(
            "# Real\n"
            "```markdown\n[fake](missing.md)\n# Fake\n```\n"
            "~~~~\n![fake](missing.png)\n~~~\n[still fake](missing.md)\n~~~~\n"
            "`[inline](missing.md)` and ``[inline ` nested](missing.md)``\n"
            "<!-- [comment](missing.md)\n# Comment\n-->\n"
            "<code>[raw](missing.md)</code>\n"
            "\n    [indented](missing.md)\n\n"
            r"\[escaped](missing.md)" + "\n"
            "Bare placeholders [NOT_A_LINK] and `docs/a.md`.\n"
            "[real](#real)\n", 1)

    def test_fenced_and_inline_html_do_not_create_anchors(self):
        self.put("target.md", '# Real\n```html\n<a id="fake"></a>\n# Fake\n```\n'
                 '`<a id="inline"></a>`\n<!-- <a id="comment"></a> -->\n')
        issues, _ = self.check("[a](target.md#fake)\n[b](target.md#inline)\n[c](target.md#comment)\n")
        self.assertEqual(3, len(issues))
        self.assertTrue(all("anchor" in issue.reason for issue in issues))

    def test_fenced_comment_literals_do_not_hide_links_after_closing_fence(self):
        for opening, closing in [("~~~", "~~~"), ("```", "````")]:
            with self.subTest(opening=opening):
                issues, counts = self.check(
                    "# Real\n" + opening + "\n<!-- literal, not an HTML comment\n"
                    + closing + "\n[real broken link](missing.md)\n")
                self.assertEqual(1, counts["links"])
                self.assertEqual(1, len(issues))
                self.assertEqual(5, issues[0].line)
                self.assertEqual("local target does not exist", issues[0].reason)

    def test_fence_markers_in_html_comments_are_not_code_blocks(self):
        issues, counts = self.check("# Real\n<!--\n~~~\n-->\n[real](missing.md)\n")
        self.assertEqual(1, counts["links"])
        self.assertEqual(1, len(issues))
        self.assertEqual(5, issues[0].line)

    def test_unclosed_fence_is_code_to_end_of_document(self):
        self.assert_clean("# Heading\n~~~\n[not a link](missing.md)\n", 0)

    def test_inline_code_in_link_label_is_not_another_link(self):
        self.assert_clean("# Home\n[`[placeholder](missing.md)`](#home)\n", 1)

    def test_line_anchors_have_fixed_line_bounds(self):
        self.put("Source.java", b"first\r\nsecond\r\nthird\r\n")
        self.put("last.txt", "one\ntwo")
        self.assert_clean("[a](Source.java#L1) [b](Source.java#L1-L3) "
                          "[c](Source.java#L3) [d](last.txt#L2)\n", 4)
        issues, _ = self.check("[zero](Source.java#L0)\n[large](Source.java#L4)\n"
                               "[reverse](Source.java#L3-L2)\n[range](Source.java#L1-L4)\n")
        self.assertEqual(4, len(issues))
        self.assertTrue(all("target has 3 lines" in issue.reason for issue in issues))

    def test_line_anchors_reject_binary_empty_and_unsupported_fragments(self):
        self.put("binary.dat", b"text\x00\ntext\n")
        self.put("invalid.bin", b"\xff\xfe\n")
        self.put("empty.txt", "")
        self.put("source.txt", "one\ntwo\n")
        issues, _ = self.check("[binary](binary.dat#L1)\n[encoding](invalid.bin#L1)\n"
                               "[empty](empty.txt#L1)\n[bad](source.txt#section)\n"
                               "[syntax](source.txt#L1-2)\n")
        self.assertEqual(5, len(issues))
        self.assertIn("binary", issues[0].reason)
        self.assertIn("UTF", issues[1].reason.upper())
        self.assertIn("0 lines", issues[2].reason)
        self.assertIn("unsupported fragment", issues[3].reason)
        self.assertIn("unsupported fragment", issues[4].reason)

    def test_missing_file_bad_slug_and_directory_fragment_have_lines(self):
        self.put("target.md", "# Good Slug\n")
        (self.root / "directory").mkdir()
        issues, counts = self.check("# Home\n\n[missing](gone.md)\n"
                                    "[bad](target.md#Good-Slug)\n[directory](directory/#thing)\n")
        self.assertEqual([3, 4, 5], [issue.line for issue in issues])
        self.assertEqual(["README.md"] * 3, [issue.source for issue in issues])
        self.assertEqual(["gone.md", "target.md#Good-Slug", "directory/#thing"],
                         [issue.target for issue in issues])
        self.assertEqual(3, counts["issues"])

    def test_outside_absolute_windows_and_unsupported_scheme_are_rejected(self):
        outside = self.workspace / "outside.md"
        outside.write_text("# Outside\n", encoding="utf-8")
        cases = ["../outside.md", "%2e%2e/outside.md", str(outside),
                 "%2Fetc/passwd", "C:/Windows/file.txt", "file:///etc/passwd",
                 "//server/share.md", "s3://bucket/file.md", r"folder\file.md"]
        for target in cases:
            with self.subTest(target=target):
                issues, _ = self.check("[bad](" + target + ")\n")
                self.assertEqual(1, len(issues))
                self.assertRegex(issues[0].reason, "escapes|absolute|scheme|backslash")

    def test_malformed_percent_utf8_and_control_characters_are_rejected(self):
        for target in ["a%2.md", "%GG.md", "%FF.md", "a%00.md", "#%ZZ"]:
            with self.subTest(target=target):
                issues, _ = self.check("[bad](" + target + ")\n")
                self.assertEqual(1, len(issues))
                self.assertIn("cannot validate local target", issues[0].reason)

    def test_symlinks_cannot_escape_root_or_hide_generated_sources(self):
        outside = self.workspace / "outside.md"
        outside.write_text("# Outside\n", encoding="utf-8")
        try:
            (self.root / "escape.md").symlink_to(outside)
        except (OSError, NotImplementedError) as error:
            self.skipTest(str(error))
        self.put("output/generated.md", "[bad](missing.md)\n")
        (self.root / "alias.md").symlink_to(self.root / "output/generated.md")
        issues, counts = self.check("[escape](escape.md#outside)\n", documents=["README.md", "alias.md"])
        self.assertEqual(1, len(issues))
        self.assertIn("escapes", issues[0].reason)
        self.assertEqual(1, counts["skipped_documents"])
        issues, counts = CHECKER.check_documents(self.root, ["escape.md"])
        self.assertEqual(1, len(issues))
        self.assertIn("document symlink escapes", issues[0].reason)
        self.assertEqual(0, counts["documents"])

    def test_external_urls_are_skipped_without_network_or_filesystem_reads(self):
        with mock.patch.object(CHECKER, "_text_file", wraps=CHECKER._text_file) as reader:
            counts = self.assert_clean(
                "[a](https://example.invalid/a#missing)\n"
                "![b](HTTP://example.invalid/missing.png)\n"
                "[c](mailto:user@example.invalid)\n"
                "[d][remote]\n[remote]: https://example.invalid/%ZZ\n", 4)
            self.assertEqual(1, reader.call_count)
        self.assertEqual(4, counts["external_links"])
        self.assertEqual(0, counts["local_links"])

    def test_exclusions_missing_sources_non_md_and_duplicates(self):
        excluded = [".git", "vendor", "node_modules", "target", "output",
                    ".worktrees", "worktrees", "worktree", ".worktree"]
        paths = []
        for directory in excluded:
            paths.append("nested/" + directory + "/bad.md")
            self.put(paths[-1], b"\x00not text")
        self.put("copy/.git", "gitdir: /not-opened\n")
        self.put("copy/README.md", b"\x00not text")
        self.put("document.MD", "# Uppercase\n")
        self.put("ignored.txt", "[bad](missing.md)\n")
        (self.root / "directory.md").mkdir()
        issues, counts = CHECKER.check_documents(
            self.root, paths + ["copy/README.md", "document.MD", "document.MD",
                               "ignored.txt", "directory.md", "deleted.md"])
        self.assertEqual([], issues)
        self.assertEqual(1, counts["documents"])
        self.assertEqual(13, counts["skipped_documents"])

    def test_generated_targets_must_exist_but_are_never_read(self):
        self.put("target/site/existing.html", b"\x00binary not opened")
        self.put("output/generated.md", b"\x00binary not opened")
        with mock.patch.object(CHECKER, "_text_file", wraps=CHECKER._text_file) as reader:
            issues, counts = self.check(
                "[missing Javadoc](target/site/apidocs/index.html)\n"
                "[existing](target/site/existing.html)\n"
                "[fragment](output/generated.md#some-heading)\n")
            self.assertEqual(1, reader.call_count)
        self.assertEqual(3, counts["links"])
        self.assertEqual(2, len(issues))
        self.assertIn("does not exist", issues[0].reason)
        self.assertIn("cannot inspect fragments", issues[1].reason)

    def test_source_utf8_bom_crlf_and_non_ascii_paths(self):
        self.put("中文/目标.MD", "# 标题\n")
        self.assert_clean("\ufeff# 首页\r\n\r\n[目标](中文/目标.MD#标题)\r\n[本页](#首页)\r\n", 2)
        issues, _ = self.check(b"# Valid\n\xff\n")
        self.assertEqual(1, len(issues))
        self.assertEqual(1, issues[0].line)
        self.assertIn("cannot read Markdown as UTF-8", issues[0].reason)

    def test_undefined_references_are_errors_but_bracketed_prose_is_not(self):
        issues, counts = self.check("[ordinary prose]\n[full][missing]\n[collapsed][]\n![image][missing]\n")
        self.assertEqual(3, counts["links"])
        self.assertEqual([2, 3, 4], [issue.line for issue in issues])
        self.assertTrue(all(issue.reason == "undefined reference label" for issue in issues))

    def test_malformed_inline_links_are_not_silently_skipped(self):
        cases = ["[x](missing.md", "[x](<missing.md)", "[x](a b.md)",
                 '[x](file.md "unclosed title)', "[x](unbalanced(a.md)",
                 '[x](file.md "title" extra)', "[x][unclosed"]
        for content in cases:
            with self.subTest(content=content):
                issues, counts = self.check(content + "\n")
                self.assertEqual(1, counts["links"])
                self.assertEqual(1, len(issues))
                self.assertRegex(issues[0].reason, "malformed|unclosed")

    def test_multiline_reference_definition_is_explicitly_unsupported(self):
        issues, _ = self.check("[reference]:\n  target.md\n")
        self.assertEqual(1, len(issues))
        self.assertIn("multiline definitions are unsupported", issues[0].reason)

    def test_multiline_inline_link_and_escaped_title(self):
        self.put("target.md", "# Target\n")
        self.assert_clean('[label\non two lines](target.md#target\n"title")\n', 1)

    def test_no_heading_style_rules_are_enforced(self):
        self.assert_clean("# First\n# Second\n#\n##\n", 0)

    def test_issues_and_counts_are_deterministic_without_git(self):
        self.put("z.md", "[second](b.md)\n[first](a.md)\n")
        self.put("a.md", "[third](c.md)\n")
        with mock.patch.object(CHECKER.subprocess, "run", side_effect=AssertionError("API must not invoke Git")):
            forward = CHECKER.check_documents(self.root, ["z.md", "a.md"])
            reverse = CHECKER.check_documents(self.root, ["a.md", "z.md", "a.md"])
        self.assertEqual(forward, reverse)
        self.assertEqual([("a.md", 1), ("z.md", 1)],
                         [(issue.source, issue.line) for issue in forward[0]])
        self.assertEqual(3, forward[1]["links"])

    def test_git_discovery_is_names_only_and_nul_safe(self):
        output = "document.MD\0space name.md\0中文.md\0deleted.md\0output/bad.md\0".encode("utf-8")
        result = subprocess.CompletedProcess([], 0, stdout=output, stderr=b"")
        with mock.patch.object(CHECKER.subprocess, "run", return_value=result) as run:
            names = CHECKER.discover_documents(self.root)
        self.assertEqual(["document.MD", "space name.md", "中文.md", "deleted.md", "output/bad.md"], names)
        run.assert_called_once_with(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.md", "*.MD"],
            cwd=str(self.root), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)

    def test_git_failure_is_clear_and_does_not_initialize_anything(self):
        result = subprocess.CompletedProcess([], 128, stdout=b"", stderr=b"not a git repository\n")
        with mock.patch.object(CHECKER.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(ValueError, "git ls-files failed: not a git repository"):
                CHECKER.discover_documents(self.root)
        self.assertFalse((self.root / ".git").exists())

    def test_cli_explicit_documents_bypass_git_from_another_cwd(self):
        self.put("README.md", "# Home\n[guide](guide.MD#guide)\n")
        self.put("guide.MD", "# Guide\n")
        before = sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob("*"))
        result = self.cli("--root", str(self.root), "--document", "README.md",
                          "--document", "guide.MD", "--json")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        parsed = json.loads(result.stdout)
        self.assertEqual(dict(documents=2, skipped_documents=0, links=1,
                              local_links=1, external_links=0, issues=0), parsed["counts"])
        self.assertEqual([], parsed["issues"])
        self.assertEqual("", result.stderr)
        self.assertEqual(before, sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob("*")))

    def test_cli_issues_have_nonzero_exit_json_and_clear_text(self):
        self.put("README.md", "# Home\n[broken](missing.md)\n")
        arguments = ("--root", str(self.root), "--document", "README.md")
        result = self.cli(*arguments, "--json")
        self.assertEqual(1, result.returncode)
        self.assertEqual([dict(source="README.md", line=2, target="missing.md",
                               reason="local target does not exist")], json.loads(result.stdout)["issues"])
        result = self.cli(*arguments)
        self.assertEqual(1, result.returncode)
        self.assertIn("Checked 1 Markdown documents: 1 links", result.stdout)
        self.assertIn('README.md:2: "missing.md": local target does not exist', result.stdout)
        self.assertEqual(result.stdout, self.cli(*arguments).stdout)

    def test_cli_default_requires_git_but_does_not_create_a_repository(self):
        result = self.cli("--root", str(self.root), "--json")
        self.assertEqual(2, result.returncode)
        self.assertIn("cannot list Markdown files with Git", json.loads(result.stdout)["error"])
        self.assertEqual([], list(self.root.iterdir()))

    @unittest.skipIf(os.name == "nt", "temporary shebang executable is a POSIX CLI fixture")
    def test_default_cli_root_is_script_parent_not_current_directory(self):
        copied_script = self.root / "scripts/check-docs.py"
        copied_script.parent.mkdir()
        shutil.copyfile(SCRIPT, copied_script)
        self.put("README.md", "# Home\n[self](#home)\n")
        self.put("output/bad.md", b"\x00must not be read")
        bin_path = self.workspace / "fake-bin"
        bin_path.mkdir()
        fake_git = bin_path / "git"
        fake_git.write_text(
            "#!" + sys.executable + "\nimport sys\n"
            "expected = ['ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', '*.md', '*.MD']\n"
            "if sys.argv[1:] != expected: sys.exit(97)\n"
            "sys.stdout.buffer.write(b'README.md\\0output/bad.md\\0deleted.md\\0')\n",
            encoding="utf-8")
        fake_git.chmod(0o700)
        result = self.cli("--json", script=copied_script, env={"PATH": str(bin_path)})
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertEqual(dict(documents=1, skipped_documents=2, links=1,
                              local_links=1, external_links=0, issues=0), json.loads(result.stdout)["counts"])
        self.assertFalse((self.root / ".git").exists())

    def test_parent_links_do_not_treat_repository_root_as_nested_git(self):
        self.put(".git", "test-only metadata marker; no Git repository initialized\n")
        self.put("guide.md", "# Guide\n")
        self.assert_clean("[up](../guide.md#guide)\n", 1, name="docs/index.md")

    def test_multiline_inline_code_cannot_define_reference_links(self):
        self.assert_clean("``placeholder\n[hidden]: missing.md\n``\n[hidden]\n", 0)

    def test_tilde_prefixed_relative_filename_is_not_an_absolute_path(self):
        self.put("~draft.md", "# Draft\n")
        self.assert_clean("[draft](~draft.md#draft)\n", 1)

    def test_line_bounds_use_physical_newlines_not_page_breaks(self):
        self.put("source.txt", "first\fpage\nsecond\n")
        self.assert_clean("[second](source.txt#L2)\n", 1)
        issues, _ = self.check("[past end](source.txt#L3)\n")
        self.assertEqual(1, len(issues))
        self.assertIn("target has 2 lines", issues[0].reason)

    def test_unicode_and_escaped_reference_labels(self):
        self.put("target.md", "# Target\n")
        self.assert_clean(
            "[unicode][STRASSE]\n" + r"[escaped][a\]b]" + "\n"
            "[Straße]: target.md#target\n" + r"[a\]b]: target.md#target" + "\n", 2)

    def test_outside_document_path_is_not_read(self):
        outside = self.workspace / "outside.md"
        outside.write_bytes(b"\x00must not be read")
        with mock.patch.object(CHECKER, "_text_file", side_effect=AssertionError("outside read")):
            issues, counts = CHECKER.check_documents(self.root, [outside])
        self.assertEqual(1, len(issues))
        self.assertIn("document path is outside", issues[0].reason)
        self.assertEqual(0, counts["documents"])

    def test_cli_nonexistent_root_is_an_input_error(self):
        result = self.cli("--root", str(self.root / "does-not-exist"), "--document", "README.md", "--json")
        self.assertEqual(2, result.returncode)
        self.assertIn("root is not an existing directory", json.loads(result.stdout)["error"])

    def test_main_defaults_to_script_parent_without_reading_real_docs(self):
        with mock.patch.object(CHECKER, "discover_documents", return_value=[]) as discover:
            with mock.patch.object(CHECKER, "check_documents", return_value=([], dict(
                    documents=0, skipped_documents=0, links=0, local_links=0,
                    external_links=0, issues=0))) as check:
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, CHECKER.main([]))
        discover.assert_called_once_with(SCRIPT.parent.parent)
        check.assert_called_once_with(SCRIPT.parent.parent, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
