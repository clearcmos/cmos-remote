"""Guards on the pinned dependency set.

install.sh installs from requirements.lock with --require-hashes, so a direct
dependency added to requirements.txt but never compiled into the lock would not
be installed at all, and the server would fail at import on a fresh host rather
than at install time. These tests turn that into a CI failure instead.

Regenerate the lock after editing requirements.txt:
    uv pip compile requirements.txt -o requirements.lock --universal \\
        --generate-hashes --python-version 3.11
"""

import pathlib
import re

SERVER_DIR = pathlib.Path(__file__).resolve().parents[1]
REQUIREMENTS = SERVER_DIR / "requirements.txt"
LOCK = SERVER_DIR / "requirements.lock"

# "fastapi>=0.115.0" -> "fastapi"; "uvicorn[standard]>=0.32.0" -> "uvicorn"
REQUIREMENT_NAME = re.compile(r"^([A-Za-z0-9._-]+)")


def normalize(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def direct_requirements() -> list[str]:
    names = []
    for line in REQUIREMENTS.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = REQUIREMENT_NAME.match(line)
        assert match, f"unparsable requirement: {line}"
        names.append(normalize(match.group(1)))
    return names


def locked_packages() -> set[str]:
    packages = set()
    for line in LOCK.read_text().splitlines():
        if line.startswith("#") or line.startswith(" ") or not line.strip():
            continue
        match = REQUIREMENT_NAME.match(line)
        if match:
            packages.add(normalize(match.group(1)))
    return packages


def test_every_direct_requirement_is_locked():
    missing = sorted(set(direct_requirements()) - locked_packages())
    assert not missing, f"requirements.txt entries missing from requirements.lock: {missing}"


def test_lock_is_fully_hashed():
    # --require-hashes fails the whole install if a single entry lacks hashes.
    entries = [
        line
        for line in LOCK.read_text().splitlines()
        if line and not line.startswith(("#", " ")) and "==" in line
    ]
    assert entries, "requirements.lock has no pinned entries"
    for entry in entries:
        assert entry.rstrip().endswith("\\"), f"entry without hashes: {entry}"


def test_lock_pins_exact_versions():
    for line in LOCK.read_text().splitlines():
        if line.startswith("#") or line.startswith(" ") or not line.strip():
            continue
        assert "==" in line, f"unpinned entry in lock: {line}"
