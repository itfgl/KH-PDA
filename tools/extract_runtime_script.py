from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android-entry" / "android" / "app" / "src" / "main" / "java" / "com" / "kaihang" / "scanner" / "MainActivity.java"
TARGET = ROOT / "tmp_android_runtime_check.js"


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    parts = [
        json.loads(f'"{match}"')
        for match in re.findall(r'script\.append\("((?:[^"\\]|\\.)*)"\);', text)
    ]
    TARGET.write_text("\n".join(parts), encoding="utf-8")
    print(TARGET)
    print(f"parts={len(parts)}")


if __name__ == "__main__":
    main()
