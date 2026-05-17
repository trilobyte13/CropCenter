# CropCenter

Android crop / rotate editor for JPEG and PNG photos with full Samsung Ultra HDR support — preserves the gain map,
SEFT Revert chain (when present), camera EXIF identity, and MPF multi-picture metadata across crops, rotations, and
Apply-External-Edit grafts.

- **Spec**: [REQUIREMENTS.md](REQUIREMENTS.md) — authoritative behavior contract.
- **Style guide**: [CLAUDE.md](CLAUDE.md) — codebase conventions, audit rules, canonical helpers.
- **Tooling**: [scripts/README.md](scripts/README.md) — audit / refactor / verification / HDR-inspection scripts.

## Build

```bash
./gradlew.bat compileDebugJavaWithJavac
./gradlew.bat :app:testDebugUnitTest
```

Min SDK 35, target / compile SDK 36, Java 21, AGP 9.1.1, Gradle 9.3.1.

## Audit

```bash
python scripts/audit.py
```

Exit code 0 means every failing check is clean; `reflow` and `lsloc` are advisory metrics that always print.
