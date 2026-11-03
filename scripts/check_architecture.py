#!/usr/bin/env python3
"""Check the application dependency boundaries without the Android toolchain."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app" / "src" / "main" / "kotlin" / "org" / "starfall" / "generative"
IMPORT = re.compile(r"^\s*import\s+([\w.*]+)", re.MULTILINE)


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


def main() -> int:
    errors: list[str] = []
    files = sorted(SOURCE_ROOT.rglob("*.kt"))
    for path in files:
        area, feature = package_area(path)
        for line, imported in imports(path):
            if area in {"shared", "integrations"} and (
                imported.startswith("org.starfall.generative.database.")
                or imported.startswith("org.starfall.generative.features.")
                or imported.startswith("org.starfall.generative.navigation.")
                or imported.startswith("org.starfall.generative.appwiring.")
                or imported in {
                    "org.starfall.generative.MainActivity",
                    "org.starfall.generative.GenerativeApplication",
                }
                or imported == "org.starfall.generative.BuildConfig"
            ):
                errors.append(f"{path.relative_to(ROOT)}:{line}: {area} imports upward dependency {imported}")

            if area in {"database", "integrations"} and (
                imported.startswith("androidx.compose.foundation.")
                or imported.startswith("androidx.compose.material.")
                or imported.startswith("androidx.compose.material3.")
                or imported.startswith("androidx.compose.ui.")
            ):
                errors.append(f"{path.relative_to(ROOT)}:{line}: {area} imports Compose rendering {imported}")

            if area == "features" and feature and imported.startswith("org.starfall.generative.features."):
                imported_parts = imported.split(".")
                imported_feature = imported_parts[4] if len(imported_parts) > 4 else None
                if imported_feature and imported_feature != feature:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{line}: feature {feature} imports feature {imported_feature}"
                    )

            if area == "features" and feature and imported.startswith("org.starfall.generative.features."):
                imported_parts = imported.split(".")
                imported_feature = imported_parts[4] if len(imported_parts) > 4 else None
                imported_package = ".".join(imported_parts[5:])
                if imported_feature == feature and imported_package.startswith("model.") and (
                    ".ui." in imported_package or ".logic." in imported_package
                ):
                    errors.append(f"{path.relative_to(ROOT)}:{line}: feature model imports UI/logic {imported}")

    if errors:
        print("Architecture dependency violations:")
        print("\n".join(errors))
        return 1
    print(f"Architecture dependency boundaries passed ({len(files)} Kotlin files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
