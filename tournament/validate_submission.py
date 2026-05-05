#!/usr/bin/env python3
"""
Validate a MicroRTS competition submission.

Checks:
  1. metadata.json exists and has required fields
  2. Agent Java file exists
  3. Java file has correct package declaration
  4. Java file has UnitTypeTable constructor
  5. No forbidden imports/APIs

Usage:
    python3 tournament/validate_submission.py submissions/example-team/
    python3 tournament/validate_submission.py --all   # validate all submissions
"""

import json
import os
import re
import sys
from pathlib import Path

REQUIRED_METADATA_FIELDS = [
    "team_name", "display_name", "agent_class", "agent_file",
    "model_provider", "model_name"
]

VALID_PROVIDERS = ["ollama", "gemini", "openai", "deepseek", "none"]

# Allowed base packages for submissions
ALLOWED_PACKAGES = [
    "ai.abstraction.submissions",
    "ai.mcts.submissions",
]

# Forbidden Java patterns (security)
FORBIDDEN_PATTERNS = [
    (r'Runtime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec', "Runtime.exec - spawning processes"),
    (r'new\s+ProcessBuilder', "ProcessBuilder - spawning processes"),
    (r'new\s+ServerSocket', "ServerSocket - network servers"),
    (r'System\s*\.\s*exit', "System.exit - terminating JVM"),
    (r'Files\s*\.\s*delete', "Files.delete - deleting files"),
    (r'\.delete\s*\(\s*\)', ".delete() - deleting files"),
    (r'new\s+ClassLoader', "ClassLoader - dynamic class loading"),
    (r'Class\s*\.\s*forName', "Class.forName - reflection"),
    (r'new\s+Thread\s*\(', "Thread creation - multithreading"),
    (r'\.start\s*\(\s*\).*thread', "Thread.start - multithreading"),
]

SKIP_DIRS = {"_template"}


def validate_submission(submission_dir):
    """Validate a single submission. Returns (ok, errors)."""
    errors = []
    submission_dir = Path(submission_dir)
    team_folder = submission_dir.name

    if team_folder in SKIP_DIRS:
        return True, []

    # 1. Check metadata.json exists
    metadata_path = submission_dir / "metadata.json"
    if not metadata_path.exists():
        return False, [f"metadata.json not found in {submission_dir}"]

    # 2. Parse metadata.json
    try:
        with open(metadata_path) as f:
            metadata = json.load(f)
    except json.JSONDecodeError as e:
        return False, [f"metadata.json is invalid JSON: {e}"]

    # 3. Check required fields
    for field in REQUIRED_METADATA_FIELDS:
        if field not in metadata:
            errors.append(f"Missing required field: {field}")
        elif not metadata[field] or not str(metadata[field]).strip():
            errors.append(f"Field '{field}' is empty")

    if errors:
        return False, errors

    team_name = metadata["team_name"]
    agent_class = metadata["agent_class"]
    agent_file = metadata["agent_file"]
    model_provider = metadata["model_provider"]

    # 4. team_name must match folder name
    if team_name != team_folder:
        errors.append(
            f"team_name '{team_name}' does not match folder name '{team_folder}'"
        )

    # 5. team_name format check
    if not re.match(r'^[a-z0-9][a-z0-9-]*[a-z0-9]$', team_name) and len(team_name) > 1:
        errors.append(
            f"team_name must be lowercase alphanumeric with hyphens: '{team_name}'"
        )

    # 6. model_provider check
    if model_provider not in VALID_PROVIDERS:
        errors.append(
            f"model_provider must be one of {VALID_PROVIDERS}, got '{model_provider}'"
        )

    # 7. Agent file exists
    agent_path = submission_dir / agent_file
    if not agent_path.exists():
        errors.append(f"Agent file not found: {agent_file}")
        return False, errors

    # 8. Read and validate Java source
    java_source = agent_path.read_text()

    # Package declaration check
    package_name = team_name.replace("-", "_")
    expected_packages = [f"{base}.{package_name}" for base in ALLOWED_PACKAGES]
    package_match = re.search(r'^\s*package\s+([\w.]+)\s*;', java_source, re.MULTILINE)
    if not package_match:
        errors.append("No package declaration found in Java file")
    elif package_match.group(1) not in expected_packages:
        errors.append(
            f"Package must be one of {expected_packages}, "
            f"found '{package_match.group(1)}'"
        )

    # Class declaration check
    class_match = re.search(
        rf'public\s+class\s+{re.escape(agent_class)}\s+extends\s+\w+',
        java_source
    )
    if not class_match:
        errors.append(
            f"Class '{agent_class}' not found or doesn't extend a base class"
        )

    # Constructor check
    constructor_match = re.search(
        rf'public\s+{re.escape(agent_class)}\s*\(\s*UnitTypeTable\s+\w+\s*\)',
        java_source
    )
    if not constructor_match:
        errors.append(
            f"Constructor '{agent_class}(UnitTypeTable)' not found"
        )

    # 9. Security: forbidden patterns
    for pattern, description in FORBIDDEN_PATTERNS:
        if re.search(pattern, java_source, re.IGNORECASE):
            errors.append(f"Forbidden API: {description}")

    return len(errors) == 0, errors


def find_all_submissions(submissions_dir="submissions"):
    """Find all submission directories."""
    submissions_dir = Path(submissions_dir)
    if not submissions_dir.exists():
        return []
    return [
        d for d in sorted(submissions_dir.iterdir())
        if d.is_dir() and d.name not in SKIP_DIRS and not d.name.startswith(".")
    ]


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 tournament/validate_submission.py <submission_dir>")
        print("       python3 tournament/validate_submission.py --all")
        sys.exit(1)

    if sys.argv[1] == "--all":
        submissions = find_all_submissions()
        if not submissions:
            print("No submissions found.")
            sys.exit(0)

        all_ok = True
        for sub_dir in submissions:
            ok, errors = validate_submission(sub_dir)
            status = "PASS" if ok else "FAIL"
            print(f"  [{status}] {sub_dir.name}")
            if errors:
                for err in errors:
                    print(f"         {err}")
                all_ok = False

        sys.exit(0 if all_ok else 1)
    else:
        submission_dir = sys.argv[1]
        if not os.path.isdir(submission_dir):
            print(f"Error: {submission_dir} is not a directory")
            sys.exit(1)

        ok, errors = validate_submission(submission_dir)
        if ok:
            print(f"PASS: {submission_dir}")
            sys.exit(0)
        else:
            print(f"FAIL: {submission_dir}")
            for err in errors:
                print(f"  - {err}")
            sys.exit(1)


if __name__ == "__main__":
    main()
