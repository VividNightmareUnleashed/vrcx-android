#!/usr/bin/env python3
"""Reject growth in every suppression section of a Detekt baseline."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


MAX_BASELINE_BYTES = 16 * 1024 * 1024
MAX_REPORTED_ADDITIONS = 50
_UNSAFE_XML_DECLARATION = re.compile(br"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)
_BASELINE_SECTIONS = ("ManuallySuppressedIssues", "CurrentIssues")


class BaselineError(ValueError):
    """Raised when a baseline cannot be read or has an invalid structure."""


@dataclass(frozen=True)
class BaselineEntry:
    """A suppression ID together with the section that gives it meaning."""

    section: str
    issue_id: str


@dataclass(frozen=True)
class BaselineComparison:
    """The entry-level difference between a base and head baseline."""

    base_entries: frozenset[BaselineEntry] | None
    head_entries: frozenset[BaselineEntry]

    @property
    def is_bootstrap(self) -> bool:
        return self.base_entries is None

    @property
    def added_entries(self) -> frozenset[BaselineEntry]:
        if self.base_entries is None:
            return frozenset()
        return self.head_entries - self.base_entries

    @property
    def removed_entries(self) -> frozenset[BaselineEntry]:
        if self.base_entries is None:
            return frozenset()
        return self.base_entries - self.head_entries


def _read_bounded(path: Path, *, allow_missing: bool) -> bytes | None:
    try:
        with path.open("rb") as baseline_file:
            content = baseline_file.read(MAX_BASELINE_BYTES + 1)
    except FileNotFoundError:
        if allow_missing:
            return None
        raise BaselineError(f"baseline does not exist: {path}") from None
    except OSError as error:
        raise BaselineError(f"could not read baseline {path}: {error}") from None

    if len(content) > MAX_BASELINE_BYTES:
        raise BaselineError(
            f"baseline exceeds the {MAX_BASELINE_BYTES}-byte safety limit: {path}"
        )
    return content


def load_baseline_entries(
    path: Path,
    *,
    allow_missing: bool = False,
) -> frozenset[BaselineEntry] | None:
    """Load unique, section-qualified suppression IDs from a Detekt baseline."""

    content = _read_bounded(path, allow_missing=allow_missing)
    if content is None:
        return None

    if _UNSAFE_XML_DECLARATION.search(content):
        raise BaselineError(f"DTD and entity declarations are not allowed in baseline: {path}")

    try:
        root = ElementTree.fromstring(content)
    except (ElementTree.ParseError, LookupError, ValueError) as error:
        raise BaselineError(f"malformed baseline XML {path}: {error}") from None

    if root.tag != "SmellBaseline":
        raise BaselineError(
            f"invalid baseline root in {path}: expected 'SmellBaseline', got {root.tag!r}"
        )

    if root.text and root.text.strip():
        raise BaselineError(f"invalid baseline {path}: unexpected text in SmellBaseline")

    sections: dict[str, ElementTree.Element] = {}
    for section in root:
        if section.tag not in _BASELINE_SECTIONS:
            raise BaselineError(
                f"invalid baseline {path}: unexpected {section.tag!r} element"
            )
        if section.tag in sections:
            raise BaselineError(
                f"invalid baseline {path}: expected exactly one {section.tag} element"
            )
        if section.tail and section.tail.strip():
            raise BaselineError(
                f"invalid baseline {path}: unexpected text after {section.tag}"
            )
        sections[section.tag] = section

    for section_name in _BASELINE_SECTIONS:
        if section_name not in sections:
            raise BaselineError(
                f"invalid baseline {path}: expected exactly one {section_name} element"
            )

    entries: set[BaselineEntry] = set()
    for section_name in _BASELINE_SECTIONS:
        section = sections[section_name]
        if section.text and section.text.strip():
            raise BaselineError(
                f"invalid baseline {path}: unexpected text in {section_name}"
            )
        for issue in section:
            if issue.tag != "ID" or list(issue):
                raise BaselineError(
                    f"invalid baseline {path}: {section_name} may contain only plain ID elements"
                )

            issue_id = (issue.text or "").strip()
            if not issue_id:
                raise BaselineError(
                    f"invalid baseline {path}: {section_name} contains an empty ID"
                )
            if issue.tail and issue.tail.strip():
                raise BaselineError(f"invalid baseline {path}: unexpected text after an ID")
            entries.add(BaselineEntry(section_name, issue_id))

    return frozenset(entries)


def compare_baselines(base_path: Path, head_path: Path) -> BaselineComparison:
    """Compare baseline sets, permitting a missing base for first-time bootstrap."""

    # Always validate the checked-in head, including when no baseline existed at base.
    head_entries = load_baseline_entries(head_path)
    base_entries = load_baseline_entries(base_path, allow_missing=True)
    assert head_entries is not None
    return BaselineComparison(base_entries, head_entries)


def _format_entry(entry: BaselineEntry) -> str:
    return f"{entry.section}/{json.dumps(entry.issue_id, ensure_ascii=False)}"


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Reject suppression entries added to a Detekt baseline. "
            "A missing base file is allowed for initial bootstrap."
        )
    )
    parser.add_argument("base", type=Path, help="baseline file from the base revision")
    parser.add_argument("head", type=Path, help="baseline file from the head revision")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)

    try:
        comparison = compare_baselines(args.base, args.head)
    except BaselineError as error:
        print(f"Detekt baseline check failed: {error}", file=sys.stderr)
        return 2

    if comparison.is_bootstrap:
        print(
            "Detekt baseline bootstrap allowed: base file is absent; "
            f"head contains {len(comparison.head_entries)} unique suppression entries."
        )
        return 0

    added = sorted(
        comparison.added_entries,
        key=lambda entry: (entry.section, entry.issue_id),
    )
    if added:
        print(
            f"Detekt baseline grew by {len(added)} unique suppression entries:",
            file=sys.stderr,
        )
        for entry in added[:MAX_REPORTED_ADDITIONS]:
            print(f"  + {_format_entry(entry)}", file=sys.stderr)
        omitted = len(added) - MAX_REPORTED_ADDITIONS
        if omitted > 0:
            print(f"  ... and {omitted} more", file=sys.stderr)
        return 1

    assert comparison.base_entries is not None
    print(
        "Detekt baseline did not grow: "
        f"{len(comparison.base_entries)} -> {len(comparison.head_entries)} "
        "unique suppression entries "
        f"({len(comparison.removed_entries)} removed)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
