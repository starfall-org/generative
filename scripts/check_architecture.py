#!/usr/bin/env python3
"""Check the application dependency boundaries without the Android toolchain."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app" / "src" / "main" / "kotlin" / "org" / "starfall" / "generative"
IMPORT = re.compile(r"^\s*import\s+([\w.*]+)", re.MULTILINE)
UPWARD_PREFIXES = (
    "org.starfall.generative.database.",
    "org.starfall.generative.features.",
    "org.starfall.generative.navigation.",
    "org.starfall.generative.appwiring.",
)
COMPOSE_PREFIXES = (
    "androidx.compose.foundation.",
    "androidx.compose.material.",
    "androidx.compose.material3.",
    "androidx.compose.ui.",
)


def package_area(path: Path) -> tuple[str, str | None]:
    relative = path.relative_to(SOURCE_ROOT)
    parts = relative.parts
    if not parts:
        return "root", None
    area = parts[0]
    feature = parts[1] if area == "features" and len(parts) > 1 else None
    return area, feature


def imports(path: Path) -> list[tuple[int, str]]:
    text = path.read_text(encoding="utf-8")
    return [
        (text[: match.start()].count("\n") + 1, match.group(1))
        for match in IMPORT.finditer(text)
    ]


def shared_dependency_violation(area: str, imported: str, location: str) -> str | None:
    if area not in {"shared", "integrations"}:
        return None
    prohibited = imported.startswith(UPWARD_PREFIXES) or imported in {
        "org.starfall.generative.MainActivity",
        "org.starfall.generative.GenerativeApplication",
        "org.starfall.generative.BuildConfig",
    }
    if prohibited:
        return f"{location}: {area} imports upward dependency {imported}"
    return None


def compose_dependency_violation(area: str, imported: str, location: str) -> str | None:
    if area in {"database", "integrations"} and imported.startswith(COMPOSE_PREFIXES):
        return f"{location}: {area} imports Compose rendering {imported}"
    return None


def imported_feature(imported: str) -> tuple[str, str] | None:
    if not imported.startswith("org.starfall.generative.features."):
        return None
    parts = imported.split(".")
    if len(parts) <= 4:
        return None
    return parts[4], ".".join(parts[5:])


def cross_feature_violation(
    area: str, feature: str | None, imported: str, location: str
) -> str | None:
    target = imported_feature(imported)
    if area == "features" and feature and target and target[0] != feature:
        return f"{location}: feature {feature} imports feature {target[0]}"
    return None


def model_boundary_violation(
    area: str, feature: str | None, imported: str, location: str
) -> str | None:
    target = imported_feature(imported)
    if area != "features" or not feature or not target or target[0] != feature:
        return None
    if target[1].startswith("model.") and (".ui." in target[1] or ".logic." in target[1]):
        return f"{location}: feature model imports UI/logic {imported}"
    return None


def import_violations(
    area: str, feature: str | None, imported: str, location: str
) -> list[str]:
    violations: list[str] = []
    checks = (
        shared_dependency_violation(area, imported, location),
        compose_dependency_violation(area, imported, location),
        cross_feature_violation(area, feature, imported, location),
        model_boundary_violation(area, feature, imported, location),
    )
    violations.extend(check for check in checks if check is not None)
    return violations


def main() -> int:
    errors: list[str] = []
    files = sorted(SOURCE_ROOT.rglob("*.kt"))
    for path in files:
        area, feature = package_area(path)
        for line, imported in imports(path):
            location = f"{path.relative_to(ROOT)}:{line}"
            errors.extend(import_violations(area, feature, imported, location))

    if errors:
        print("Architecture dependency violations:")
        print("\n".join(errors))
        return 1
    print(f"Architecture dependency boundaries passed ({len(files)} Kotlin files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
