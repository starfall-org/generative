#!/usr/bin/env python3
"""Find the preceding stable GitHub Release from published stable tag names on stdin."""
from __future__ import annotations

import re
import sys

VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def previous_stable_release(current_tag: str, published_stable_tags: list[str]) -> str | None:
    current_match = VERSION.fullmatch(current_tag)
    if current_match is None:
        raise ValueError(f"Not a stable version: {current_tag}")
    current_version = tuple(map(int, current_match.groups()))
    candidates = []
    for tag in published_stable_tags:
        tag = tag.strip()
        match = VERSION.fullmatch(tag)
        if match is not None:
            version = tuple(map(int, match.groups()))
            if version < current_version:
                candidates.append((version, tag))
    return max(candidates)[1] if candidates else None


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: previous-stable-release.py CURRENT_TAG < PUBLISHED_STABLE_TAGS")
    previous = previous_stable_release(sys.argv[1], sys.stdin.read().splitlines())
    if previous is not None:
        print(previous)
