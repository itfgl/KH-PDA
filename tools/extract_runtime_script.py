from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNTIME_DIR = ROOT / "android-entry" / "android" / "app" / "src" / "main" / "assets" / "runtime"
SOURCES = [
    RUNTIME_DIR / "client-runtime.core.js",
    RUNTIME_DIR / "client-runtime.bootstrap.js",
]
TARGET = ROOT / "tmp_android_runtime_check.js"


def main() -> None:
    runtime_values = {
        "buildTime": "",
        "versionName": "",
        "versionCode": 0,
        "pageActionsApi": "/api/test/page-actions",
        "defaultServerBase": "",
        "defaultUpdateBase": "",
        "shouldBootstrap": False,
        "khToken": "",
        "khAuth": "basic",
        "khRole": "",
        "khApp": "",
        "khPaper": "",
        "khLayout": "",
        "redirect": "",
        "nocobaseStoragePrefix": "NOCOBASE_",
        "defaultStorageAppName": "main",
    }
    parts = [f"(function(){{window.__khRuntimeValues={json.dumps(runtime_values, ensure_ascii=False)};}})();"]
    for source in SOURCES:
        if source.exists():
            parts.append(source.read_text(encoding="utf-8"))
    TARGET.write_text("\n".join(parts), encoding="utf-8")
    print(TARGET)
    print(f"parts={len(parts)}")


if __name__ == "__main__":
    main()
