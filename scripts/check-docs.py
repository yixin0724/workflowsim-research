#!/usr/bin/env python3
"""Check local Markdown links without dependencies, network access, or writes.

Usage (from any working directory)::

    python3 -B /path/to/repository/scripts/check-docs.py
    python3 -B scripts/check-docs.py --root /tmp/docs --document README.md --json

Default discovery uses only ``git ls-files --cached --others --exclude-standard
-z -- '*.md' '*.MD'`` at the script's repository root. --document is repeatable,
is relative to --root, and bypasses Git. Missing/deleted, non-regular, and
non-Markdown source files are skipped. Sources beneath .git, vendor,
node_modules, target, output, or worktree directories (at any depth), including
symlinks into those directories and nested Git checkouts, are never read.

Supported subset: inline links/images with balanced or escaped parentheses,
angle-bracket destinations, optional single/double-quoted or parenthesized
titles, and full/collapsed/shortcut references. Reference definitions must fit
on one line; unsupported multiline definitions get a diagnostic. Reference
labels are whitespace-normalized and case-insensitive; the first definition
wins. Only used, valid definitions have their destinations checked. Explicit
undefined references and malformed attempted links are errors, not silently
ignored. Bare bracketed prose, fenced/standalone indented code, inline code,
and HTML comments are not links. This is a small scanner, not a full CommonMark
AST: HTML href/src, wiki-links, and Markdown extension syntax are not checked.

Markdown fragments use ATX/single-line setext headings with GitHub-style
Unicode lowercase, rendered inline text, punctuation removal (preserving '-'
and '_'), spaces as hyphens, and collision-aware duplicate suffixes. Explicit
HTML id attributes and <a name> anchors are also supported, including in local
.html/.htm targets. Other UTF-8 text files support #Lnn and #Lnn-Lmm only;
line bounds are never accepted for binary files. Empty fragments mean the top
of the target. Other unsupported fragments produce a diagnostic. Generated
link targets still need to exist, but their contents are never opened: a
fragment requiring that inspection produces an explicit diagnostic.

URL splitting precedes strict UTF-8 percent-decoding, so %23 in a filename is
not a fragment separator. Queries do not affect local filesystem lookup.
http:, https:, and mailto: links are skipped, never network-validated. Other
schemes, host-absolute paths, backslash paths, and escapes outside --root
(including symlinks) are rejected. Root-relative website URLs are deliberately
not treated as repository-relative paths; use a relative path instead.

Public API: check_documents(root, document_paths) returns (issues, counts).
Issues are sorted Issue(source, line, target, reason) named tuples; source is
root-relative and line is 1-based. Counts are documents (attempted reads),
skipped_documents (unique excluded/missing/non-Markdown sources), links,
local_links (including malformed attempts), external_links, and issues.
Duplicate source arguments are checked once. No style rules are enforced.
"""

import argparse
import html
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import string
import subprocess
import sys
import unicodedata
from typing import Dict, Iterable, List, NamedTuple, Optional, Set, Tuple, Union
from urllib.parse import unquote


EXCLUDED_DIRECTORIES = frozenset({
    ".git", "vendor", "node_modules", "target", "output",
    ".worktree", ".worktrees", "worktree", "worktrees",
})
EXTERNAL_SCHEMES = frozenset({"http", "https", "mailto"})
GIT_ARGUMENTS = [
    "git", "ls-files", "--cached", "--others", "--exclude-standard", "-z",
    "--", "*.md", "*.MD",
]
_ESCAPES = re.compile(r"\\([" + re.escape(string.punctuation) + r"])")
_SCHEME = re.compile(r"^([A-Za-z][A-Za-z0-9+.-]*):")
_REFERENCE = re.compile(r"^ {0,3}\[((?:\\.|[^\]\\\n])+)\]:[ \t]*(.*)$")
_HTML_TAG = re.compile(
    r"</?[A-Za-z][A-Za-z0-9-]*(?:\s+(?:[^'\">]|'[^']*'|\"[^\"]*\")*)?\s*/?>"
)
_LINE_ANCHOR = re.compile(r"L([0-9]+)(?:-L([0-9]+))?\Z")


class Issue(NamedTuple):
    source: str
    line: int
    target: str
    reason: str


class _Link(NamedTuple):
    start: int
    end: int
    label_start: int
    label_end: int
    target: str
    error: str = ""


def _blank(text: str) -> str:
    return "".join("\n" if char == "\n" else " " for char in text)


def _escaped(text: str, position: int) -> bool:
    before = position - 1
    while before >= 0 and text[before] == "\\":
        before -= 1
    return (position - before - 1) % 2 == 1


def _code_span(text: str, start: int) -> Optional[Tuple[int, str]]:
    opening = re.match(r"`+", text[start:])
    if not opening:
        return None
    width = len(opening.group())
    for closing in re.finditer(r"`+", text[start + width:]):
        if len(closing.group()) == width:
            stop = start + width + closing.start()
            return stop + width, text[start + width:stop].replace("\n", " ")
    return None


def _fenced_block_end(text: str, start: int) -> Optional[int]:
    end = text.find("\n", start)
    end = len(text) if end < 0 else end + 1
    opening = re.match(r"(`{3,}|~{3,})([^\n]*)", _container_text(text[start:end]).lstrip(" \t"))
    if not opening or (opening.group(1)[0] == "`" and "`" in opening.group(2)):
        return None
    marker = opening.group(1)
    for line in text[end:].splitlines(keepends=True):
        end += len(line)
        closing = re.fullmatch(r"(`{3,}|~{3,})[ \t]*\n?", _container_text(line).lstrip(" \t"))
        if closing and closing.group(1)[0] == marker[0] and len(closing.group(1)) >= len(marker):
            break
    return end


def _mask_literals(text: str, mask_code: bool = False, mask_fences: bool = False) -> str:
    """Preserve offsets; recognize fences before comment/raw-code contents."""
    result = list(text)
    position = 0
    while position < len(text):
        if mask_fences and (position == 0 or text[position - 1] == "\n"):
            end = _fenced_block_end(text, position)
            if end is not None:
                result[position:end] = _blank(text[position:end])
                position = end
                continue
        if text[position] == "`" and not _escaped(text, position):
            code = _code_span(text, position)
            if code:
                if mask_code:
                    result[position:code[0]] = _blank(text[position:code[0]])
                position = code[0]
                continue
        if text.startswith("<!--", position):
            end = text.find("-->", position + 4)
            end = len(text) if end < 0 else end + 3
            result[position:end] = _blank(text[position:end])
            position = end
            continue
        tag = _HTML_TAG.match(text, position)
        if tag:
            raw_code = re.match(r"<(pre|code|script|style)\b", tag.group(), re.I)
            if raw_code:
                closing = re.search(r"</" + raw_code.group(1) + r"\s*>",
                                    text[tag.end():], re.I)
                end = len(text) if not closing else tag.end() + closing.start()
                result[tag.end():end] = _blank(text[tag.end():end])
                position = end
                continue
            position = tag.end()
            continue
        position += 1
    return "".join(result)


def _container_text(line: str) -> str:
    line = re.sub(r"^ {0,3}(?:>[ \t]?)+", "", line)
    return re.sub(r"^ {0,3}(?:[-+*]|[0-9]+[.)])[ \t]+", "", line)


def _visible_markdown(text: str) -> str:
    """Mask code blocks; keep the exact source offsets and newlines."""
    result = []
    previous_blank = True
    indented = False
    for line in _mask_literals(text, mask_fences=True).splitlines(keepends=True):
        if (line.startswith("    ") or line.startswith("\t")) and (previous_blank or indented):
            result.append(_blank(line))
            indented = True
        elif indented and not line.strip():
            result.append(_blank(line))
        else:
            result.append(line)
            indented = False
        previous_blank = not line.strip()
    return "".join(result)


def _unescape(text: str) -> str:
    return html.unescape(_ESCAPES.sub(r"\1", text))


def _reference_key(label: str) -> str:
    return " ".join(_unescape(label).split()).casefold()


def _inline_destination(text: str, opening: int) -> Tuple[str, int, str]:
    """Read '(destination optional-title)', with bounded error recovery."""
    blank_line = re.search(r"\n[ \t]*\n", text[opening:])
    limit = len(text) if not blank_line else opening + blank_line.start()
    recovery = text.find("\n", opening, limit)
    recovery = limit if recovery < 0 else recovery
    position = opening + 1

    def fail(reason: str) -> Tuple[str, int, str]:
        attempted = text[opening + 1:recovery].strip()
        return attempted, max(opening + 1, recovery), "malformed Markdown link: " + reason

    while position < limit and text[position].isspace():
        position += 1
    start = position
    if position < limit and text[position] == "<":
        position += 1
        start = position
        while position < limit and text[position] != ">":
            if text[position] in "<\n\r":
                return fail("invalid angle-bracket destination")
            if text[position] == "\\" and position + 1 < limit and text[position + 1] in string.punctuation:
                position += 2
            else:
                position += 1
        if position >= limit:
            return fail("unclosed angle-bracket destination")
        destination = text[start:position]
        position += 1
    else:
        depth = 0
        while position < limit:
            char = text[position]
            if char == "\\" and position + 1 < limit and text[position + 1] in string.punctuation:
                position += 2
                continue
            if char.isspace():
                if depth:
                    return fail("spaces inside a destination require <...> or percent-encoding")
                break
            if char in "<>":
                return fail("use <...> around an angle-bracket destination")
            if char == "(":
                depth += 1
            elif char == ")":
                if not depth:
                    break
                depth -= 1
            position += 1
        if depth:
            return fail("unbalanced parentheses in destination")
        destination = text[start:position]
    separator = position
    while position < limit and text[position].isspace():
        position += 1
    if position < limit and text[position] == ")":
        return destination, position + 1, ""
    if position > separator and position < limit and text[position] in "\"'(":
        quote = text[position]
        closing = ")" if quote == "(" else quote
        position += 1
        while position < limit and text[position] != closing:
            if text[position] == "\\" and position + 1 < limit and text[position + 1] in string.punctuation:
                position += 2
            elif quote == "(" and text[position] == "(":
                return fail("escape parentheses inside a parenthesized title")
            else:
                position += 1
        if position >= limit:
            return fail("unclosed link title")
        position += 1
        while position < limit and text[position].isspace():
            position += 1
        if position < limit and text[position] == ")":
            return destination, position + 1, ""
        return fail("expected ')' after link title")
    if position >= limit:
        return fail("missing closing ')'")
    return fail("unexpected text after destination; encode spaces or use <...>")


def _references(text: str) -> Tuple[Dict[str, str], str, List[Tuple[int, str, str]]]:
    definitions = {}  # type: Dict[str, str]
    errors = []
    lines = []
    code_mask = _mask_literals(text, mask_code=True).splitlines(keepends=True)
    for number, (line, masked_line) in enumerate(zip(text.splitlines(keepends=True), code_mask), 1):
        definition = _REFERENCE.match(line.rstrip("\n"))
        if not definition or not masked_line.lstrip(" ").startswith("["):
            lines.append(line)
            continue
        label, body = definition.groups()
        if not body.strip():
            errors.append((number, "[" + label + "]", "reference definition needs a same-line destination; multiline definitions are unsupported"))
        else:
            wrapped = "(" + body + ")"
            destination, end, error = _inline_destination(wrapped, 0)
            if error or end != len(wrapped):
                errors.append((number, body, error or "malformed reference definition: unexpected trailing text"))
            else:
                definitions.setdefault(_reference_key(label), destination)
        lines.append(_blank(line))
    return definitions, "".join(lines), errors


def _closing_bracket(text: str, opening: int) -> Optional[int]:
    depth = 1
    position = opening + 1
    while position < len(text):
        char = text[position]
        if char == "\\" and position + 1 < len(text) and text[position + 1] in string.punctuation:
            position += 2
            continue
        if char == "`":
            code = _code_span(text, position)
            if code:
                position = code[0]
                continue
        if char == "[":
            depth += 1
        elif char == "]":
            depth -= 1
            if not depth:
                return position
        position += 1
    return None


def _link_at(text: str, opening: int, definitions: Dict[str, str]) -> Optional[_Link]:
    closing = _closing_bracket(text, opening)
    if closing is None:
        return None
    label = text[opening + 1:closing]
    after = closing + 1
    if after < len(text) and text[after] == "(":
        target, end, error = _inline_destination(text, after)
        return _Link(opening, end, opening + 1, closing, target, error)
    if after < len(text) and text[after] == "[":
        end = _closing_bracket(text, after)
        if end is None:
            return _Link(opening, after + 1, opening + 1, closing, text[after:], "unclosed reference label")
        reference = text[after + 1:end] or label
        key = _reference_key(reference)
        if key not in definitions:
            return _Link(opening, end + 1, opening + 1, closing, "[" + reference + "]", "undefined reference label")
        return _Link(opening, end + 1, opening + 1, closing, definitions[key])
    key = _reference_key(label)
    if key in definitions:
        return _Link(opening, after, opening + 1, closing, definitions[key])
    return None


def _links(text: str, definitions: Dict[str, str], offset: int = 0) -> Iterable[_Link]:
    position = 0
    while position < len(text):
        if text[position] == "\\" and position + 1 < len(text) and text[position + 1] in string.punctuation:
            position += 2
            continue
        if text[position] == "`":
            code = _code_span(text, position)
            if code:
                position = code[0]
                continue
        tag = _HTML_TAG.match(text, position)
        if tag:
            position = tag.end()
            continue
        if text[position] == "[":
            link = _link_at(text, position, definitions)
            if link:
                yield link._replace(start=link.start + offset, end=link.end + offset)
                # This also finds the image in a linked badge: [![alt](img)](url).
                yield from _links(text[link.label_start:link.label_end], definitions,
                                  offset + link.label_start)
                position = link.end
                continue
        position += 1


def _heading_text(text: str, definitions: Dict[str, str]) -> str:
    protected = []

    def protect(value: str) -> str:
        protected.append(value)
        return "\x00" + str(len(protected) - 1) + "\x00"

    def render(value: str) -> str:
        result = []
        position = 0
        while position < len(value):
            char = value[position]
            if char == "\\" and position + 1 < len(value) and value[position + 1] in string.punctuation:
                result.append(protect(value[position + 1]))
                position += 2
                continue
            if char == "`":
                code = _code_span(value, position)
                if code:
                    content = code[1]
                    if content.startswith(" ") and content.endswith(" ") and content.strip():
                        content = content[1:-1]
                    result.append(protect(content))
                    position = code[0]
                    continue
            tag = _HTML_TAG.match(value, position)
            if tag:
                position = tag.end()
                continue
            if char == "!" and value[position + 1:position + 2] == "[":
                position += 1
                char = "["
            if char == "[":
                link = _link_at(value, position, definitions)
                if link and not link.error:
                    result.append(render(value[link.label_start:link.label_end]))
                    position = link.end
                    continue
            result.append(char)
            position += 1
        return "".join(result)

    rendered = render(text)
    # Unlike a blanket punctuation strip, this preserves snake_case and code enums.
    emphasis = re.compile(r"(?<![\w\\])(_{1,3})(?=\S)(.+?)(?<=\S)\1(?!\w)", re.S)
    while True:
        updated = emphasis.sub(r"\2", rendered)
        if updated == rendered:
            break
        rendered = updated
    rendered = html.unescape(rendered)
    for index, value in enumerate(protected):
        rendered = rendered.replace("\x00" + str(index) + "\x00", value)
    return rendered


def _slug(text: str) -> str:
    result = []
    for char in text.lower():
        if char in " \t\r\n":
            result.append("-")
        elif char in "_-" or char.isalnum() or unicodedata.category(char).startswith("M"):
            result.append(char)
    return "".join(result)


class _HTMLAnchors(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.anchors = set()  # type: Set[str]

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        for key, value in attrs:
            if value is not None and (key == "id" or (tag == "a" and key == "name")):
                self.anchors.add(value)

    handle_startendtag = handle_starttag


def _html_anchors(text: str) -> Set[str]:
    parser = _HTMLAnchors()
    parser.feed(_mask_literals(text, mask_code=True))
    parser.close()
    return parser.anchors


def _markdown_anchors(text: str) -> Set[str]:
    visible = _visible_markdown(text)
    definitions, visible, _ = _references(visible)
    anchors = _html_anchors(visible)
    occurrences = {}  # type: Dict[str, int]
    previous = ""
    code_mask = _mask_literals(visible, mask_code=True).splitlines()
    for line, masked_line in zip(visible.splitlines(), code_mask):
        content = _container_text(line)
        masked_content = _container_text(masked_line)
        atx = re.match(r"^ {0,3}#{1,6}(?:[ \t]+(.*)|[ \t]*$)", content)
        heading = None
        if atx and re.match(r"^ {0,3}#", masked_content):
            heading = re.sub(r"[ \t]+#+[ \t]*$", "", atx.group(1) or "").strip()
        elif (re.fullmatch(r" {0,3}(?:=+|-+)[ \t]*", masked_content)
              and previous.strip()
              and not re.match(r"\s*(?:#|>|[-+*] |[0-9]+[.)] )", previous)):
            heading = previous.strip()
        if heading is not None:
            base = _slug(_heading_text(heading, definitions))
            slug = base
            while slug in occurrences:
                occurrences[base] += 1
                slug = base + "-" + str(occurrences[base])
            occurrences[slug] = 0
            anchors.add(slug)
        previous = content
    return anchors


def _within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _excluded(path: Path, root: Path) -> bool:
    # Collapse lexical '..' before testing ancestors; root/docs/.. is the root,
    # not a nested checkout merely because root/.git exists.
    path = Path(os.path.abspath(path))
    relative = path.relative_to(root)
    if any(part in EXCLUDED_DIRECTORIES for part in relative.parts):
        return True
    # Do not inspect nested checkouts/worktree copies, even under arbitrary names.
    return any((parent / ".git").exists() for parent in path.parents
               if parent != root and _within(parent, root))


def _text_file(path: Path) -> str:
    data = path.read_bytes()
    if any(byte < 32 and byte not in (9, 10, 12, 13) for byte in data) or b"\x7f" in data:
        raise ValueError("target is binary, not a UTF-8 text file")
    # Normalize the same newline forms as Python's text-mode reader, including BOM.
    return data.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")


def _decode_url_part(value: str) -> str:
    if re.search(r"%(?![0-9a-fA-F]{2})", value):
        raise ValueError("malformed percent-encoding")
    decoded = unquote(value, encoding="utf-8", errors="strict")
    if any(ord(char) < 32 or ord(char) == 127 for char in decoded):
        raise ValueError("control character in local destination")
    return decoded


def check_documents(root: Union[str, Path], document_paths: Iterable[Union[str, Path]]) -> Tuple[List[Issue], Dict[str, int]]:
    """Check explicitly supplied sources; never discover files or invoke Git here."""
    root = Path(root).resolve()
    issues = []  # type: List[Issue]
    counts = dict(documents=0, skipped_documents=0, links=0, local_links=0,
                  external_links=0, issues=0)
    sources = set()  # type: Set[Path]
    texts = {}  # type: Dict[Path, str]
    anchor_cache = {}  # type: Dict[Tuple[Path, bool], Set[str]]

    def read_text(path: Path) -> str:
        canonical = path.resolve()
        if canonical not in texts:
            texts[canonical] = _text_file(canonical)
        return texts[canonical]

    def local_problem(source: Path, destination: str) -> str:
        scheme = _SCHEME.match(destination)
        if scheme:
            return "unsupported URL scheme or host path: " + scheme.group(1)
        path_part, separator, fragment = destination.partition("#")
        path_part = path_part.split("?", 1)[0]
        try:
            path_part = _decode_url_part(path_part)
            fragment = _decode_url_part(fragment)
            if path_part.startswith(("/", "~/", "\\")) or re.match(r"^[A-Za-z]:", path_part):
                return "absolute host paths are not allowed; use a repository-relative path"
            if "\\" in path_part:
                return "backslash paths are not supported; use '/'"
            target = source if not path_part else source.parent / path_part
            resolved = target.resolve()
            if not _within(resolved, root):
                return "local target escapes the repository root"
            if not resolved.exists():
                return "local target does not exist"
            if not resolved.is_file() and not resolved.is_dir():
                return "local target is not a regular file or directory"
            if not separator or not fragment:
                return ""
            if resolved.is_dir():
                return "directory targets do not support fragments"
            if _excluded(target, root) or _excluded(resolved, root):
                return "cannot inspect fragments in excluded/generated/worktree targets"
            markdown = target.suffix.lower() == ".md"
            if markdown or target.suffix.lower() in {".html", ".htm"}:
                key = (resolved, markdown)
                if key not in anchor_cache:
                    content = read_text(resolved)
                    anchor_cache[key] = _markdown_anchors(content) if markdown else _html_anchors(content)
                if fragment not in anchor_cache[key]:
                    return "anchor '#" + fragment + "' does not exist"
                return ""
            line_anchor = _LINE_ANCHOR.fullmatch(fragment)
            if not line_anchor:
                return "unsupported fragment on non-Markdown target; expected #Lnn or #Lnn-Lmm"
            start = int(line_anchor.group(1))
            end = int(line_anchor.group(2) or start)
            content = read_text(resolved)
            line_count = content.count("\n") + int(bool(content) and not content.endswith("\n"))
            if not 1 <= start <= end <= line_count:
                return "line anchor out of bounds or reversed (target has " + str(line_count) + " lines)"
            return ""
        except (OSError, RuntimeError, UnicodeError, ValueError) as error:
            return "cannot validate local target: " + str(error)

    candidates = {Path(os.path.abspath(root / Path(value))) for value in document_paths}
    for path in sorted(candidates, key=str):
        source_name = path.relative_to(root).as_posix() if _within(path, root) else str(path)
        try:
            if not _within(path, root):
                issues.append(Issue(source_name, 1, str(path), "document path is outside the repository root"))
                counts["skipped_documents"] += 1
                continue
            if path.suffix.lower() != ".md" or _excluded(path, root):
                counts["skipped_documents"] += 1
                continue
            resolved = path.resolve()
            if not _within(resolved, root):
                issues.append(Issue(source_name, 1, str(path), "document symlink escapes the repository root"))
                counts["skipped_documents"] += 1
            elif _excluded(resolved, root) or not resolved.is_file():
                counts["skipped_documents"] += 1
            else:
                sources.add(path)
        except (OSError, RuntimeError, ValueError) as error:
            issues.append(Issue(source_name, 1, str(path), "cannot inspect document: " + str(error)))
            counts["skipped_documents"] += 1

    for source in sorted(sources, key=str):
        source_name = source.relative_to(root).as_posix()
        counts["documents"] += 1
        try:
            content = read_text(source)
        except (OSError, RuntimeError, UnicodeError, ValueError) as error:
            issues.append(Issue(source_name, 1, source_name, "cannot read Markdown as UTF-8 text: " + str(error)))
            continue
        visible = _visible_markdown(content)
        definitions, visible, errors = _references(visible)
        issues.extend(Issue(source_name, line, target, reason) for line, target, reason in errors)
        for link in _links(visible, definitions):
            counts["links"] += 1
            target = _unescape(link.target)
            scheme = _SCHEME.match(target)
            if not link.error and scheme and scheme.group(1).lower() in EXTERNAL_SCHEMES:
                counts["external_links"] += 1
                continue
            counts["local_links"] += 1
            reason = link.error or local_problem(source, target)
            if reason:
                line = visible.count("\n", 0, link.start) + 1
                issues.append(Issue(source_name, line, target, reason))
    issues.sort()
    counts["issues"] = len(issues)
    return issues, counts


def discover_documents(root: Path) -> List[str]:
    """Ask Git for names only; do not walk the tree or initialize a repository."""
    try:
        result = subprocess.run(GIT_ARGUMENTS, cwd=str(root), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, check=False)
    except OSError as error:
        raise ValueError("cannot list Markdown files with Git: " + str(error)) from error
    if result.returncode:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise ValueError("git ls-files failed: " + message)
    return [os.fsdecode(name) for name in result.stdout.split(b"\0") if name]


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: this script's parent repository)")
    parser.add_argument("--document", action="append", metavar="PATH",
                        help="check this source relative to --root; repeatable, bypasses Git")
    parser.add_argument("--json", action="store_true", help="print counts and issues as JSON")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if not root.is_dir():
            raise ValueError("root is not an existing directory: " + str(root))
        paths = args.document if args.document is not None else discover_documents(root)
        issues, counts = check_documents(root, paths)
    except (OSError, RuntimeError, ValueError) as error:
        if args.json:
            print(json.dumps({"error": str(error)}, ensure_ascii=False, sort_keys=True))
        else:
            print("check-docs: " + str(error), file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps({"counts": counts, "issues": [issue._asdict() for issue in issues]},
                         ensure_ascii=False, sort_keys=True, indent=2))
    else:
        print("Checked {documents} Markdown documents: {links} links "
              "({local_links} local, {external_links} external), {issues} issues; "
              "skipped {skipped_documents} sources.".format(**counts))
        for issue in issues:
            print("{}:{}: {}: {}".format(issue.source, issue.line,
                  json.dumps(issue.target, ensure_ascii=False), issue.reason))
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
