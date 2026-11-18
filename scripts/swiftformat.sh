#!/bin/sh
#
# Runs SwiftFormat — but only the version pinned in mise.toml, exiting early
# when the one on PATH is a different one.
#
# SwiftFormat is a global tool with no lockfile, and its formatting changes
# between releases. Running a newer build rewrites files the pinned build
# considers correct, so `yarn ios:format` silently produces a repo-wide diff and
# `yarn ci:format:ios` reports dozens of errors in code nobody touched. Two
# version numbers up front is a much shorter debugging session.
#
# Used by `yarn ios:format`, `yarn ci:format:ios`, the pre-commit hook and CI,
# so the pin is read from one place. Plain sh with no Node dependency: the CI
# job that runs it has no Corepack setup.
#
# Usage: scripts/swiftformat.sh [swiftformat args...]
#        scripts/swiftformat.sh --pinned-version   # print the pin and exit

set -e

CDPATH='' # so `cd` can't land somewhere else entirely
root=$(cd -- "$(dirname -- "$0")/.." && pwd)

pinned=$(sed -n 's/^swiftformat *= *"\([^"]*\)".*/\1/p' "$root/mise.toml" | head -1)
if [ -z "$pinned" ]; then
  echo "swiftformat: no 'swiftformat = \"<version>\"' pin found in mise.toml" >&2
  exit 1
fi

if [ "$1" = "--pinned-version" ]; then
  echo "$pinned"
  exit 0
fi

if ! command -v swiftformat >/dev/null 2>&1; then
  echo "swiftformat: not installed — this repo pins $pinned." >&2
  echo "  mise install   # installs the pinned build (needs mise activated in your shell)" >&2
  exit 1
fi

actual=$(swiftformat --version 2>/dev/null)
if [ "$actual" != "$pinned" ]; then
  echo "swiftformat: version mismatch — this repo pins $pinned, yours is ${actual:-unknown}." >&2
  echo "  Formatting differs between releases: this run would reformat files CI" >&2
  echo "  considers correct, and --lint would flag them as errors." >&2
  echo "  mise install   # installs the pinned build (needs mise activated in your shell)" >&2
  echo "  or download $pinned from https://github.com/nicklockwood/SwiftFormat/releases" >&2
  exit 1
fi

exec swiftformat "$@"
