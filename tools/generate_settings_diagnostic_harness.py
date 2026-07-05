from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android-entry" / "android" / "app" / "src" / "main" / "java" / "com" / "kaihang" / "scanner" / "MainActivity.java"
TARGET = ROOT / "android-entry" / "ui" / "settings-diagnostic-harness.html"


SAMPLE_ACTIONS = [
    {
        "id": "scan-fill-android-1",
        "name": "扫码填充批次",
        "enabled": True,
        "page_path": "/admin/android-diagnostic-harness",
        "trigger_type": "scan",
        "action_type": "fill_input",
        "target_selector": "input[name='serial']",
        "sort": 10,
        "options": {},
    },
    {
        "id": "button-fill-android-1",
        "name": "按钮填充备注",
        "enabled": True,
        "page_path": "/admin/android-diagnostic-harness",
        "trigger_type": "button",
        "trigger_selector": "button[data-kh-action='fill-note']",
        "action_type": "fill_input",
        "target_selector": "textarea[name='notes']",
        "value": "来自 Android 按钮动作",
        "sort": 20,
        "options": {},
    },
    {
        "id": "button-missing-android-1",
        "name": "未挂选择器按钮",
        "enabled": True,
        "page_path": "/admin/android-diagnostic-harness",
        "trigger_type": "button",
        "trigger_selector": "button[data-kh-action='missing-button']",
        "action_type": "click",
        "target_selector": "button[data-kh-action='confirm-submit']",
        "sort": 30,
        "options": {},
    },
    {
        "id": "print-single-android-1",
        "name": "打印标签核对",
        "enabled": True,
        "page_path": "/admin/android-diagnostic-harness",
        "trigger_type": "button",
        "trigger_selector": "button[data-kh-action='print-preview']",
        "action_type": "print_batch_label",
        "sort": 40,
        "options": {
            "barcode_value_selector": "input[name='barcode_content']",
            "text_value_selector": "textarea[name='print_content']",
            "paper_type": "thermal",
            "layout_preset": "standard",
        },
    },
]


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Android 动作核对 Harness</title>
  <style>
    body {{
      margin: 0;
      font-family: "Segoe UI", "PingFang SC", sans-serif;
      background:
        radial-gradient(circle at top right, rgba(14, 165, 233, 0.16), transparent 32%),
        linear-gradient(180deg, #f8fafc 0%, #e2e8f0 100%);
      color: #0f172a;
    }}
    .page {{
      max-width: 960px;
      margin: 0 auto;
      padding: 24px 20px 96px;
    }}
    .hero {{
      margin-bottom: 18px;
    }}
    .hero h1 {{
      margin: 0 0 10px;
      font-size: 30px;
      line-height: 1.12;
    }}
    .hero p {{
      margin: 0;
      color: #475569;
      line-height: 1.6;
    }}
    .toolbar {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin: 18px 0 20px;
    }}
    .toolbar button {{
      border: none;
      border-radius: 999px;
      padding: 11px 16px;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
      color: #fff;
      background: linear-gradient(135deg, #0f766e, #2563eb);
      box-shadow: 0 12px 28px rgba(15, 23, 42, 0.12);
    }}
    .toolbar button.secondary {{
      background: #e2e8f0;
      color: #0f172a;
      box-shadow: none;
    }}
    .grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 16px;
    }}
    .card {{
      background: rgba(255, 255, 255, 0.95);
      border: 1px solid rgba(148, 163, 184, 0.28);
      border-radius: 22px;
      box-shadow: 0 20px 40px rgba(15, 23, 42, 0.08);
      padding: 18px;
    }}
    .card h2 {{
      margin: 0 0 12px;
      font-size: 16px;
    }}
    .field {{
      margin-bottom: 12px;
    }}
    .field label {{
      display: block;
      margin-bottom: 6px;
      font-size: 12px;
      font-weight: 700;
      color: #475569;
    }}
    .field input,
    .field textarea {{
      width: 100%;
      padding: 12px 14px;
      border-radius: 12px;
      border: 1px solid #cbd5e1;
      font-size: 14px;
      background: #fff;
      box-sizing: border-box;
    }}
    .field textarea {{
      min-height: 92px;
      resize: vertical;
    }}
    .btn-row {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 12px;
    }}
    .btn-row button {{
      border: none;
      border-radius: 12px;
      padding: 11px 14px;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
      color: #fff;
      background: linear-gradient(135deg, #2563eb, #0284c7);
    }}
    .btn-row button.secondary {{
      color: #0f172a;
      background: #e2e8f0;
    }}
    .status {{
      margin-top: 10px;
      display: inline-flex;
      padding: 6px 10px;
      border-radius: 999px;
      background: #dcfce7;
      color: #166534;
      font-size: 12px;
      font-weight: 700;
    }}
    .table {{
      width: 100%;
      border-collapse: collapse;
      background: #fff;
      border-radius: 12px;
      overflow: hidden;
    }}
    .table th,
    .table td {{
      padding: 10px 12px;
      border-bottom: 1px solid #e2e8f0;
      text-align: left;
      font-size: 13px;
    }}
    .table th {{
      background: #eff6ff;
      color: #1d4ed8;
    }}
  </style>
</head>
<body>
  <div class="page">
    <div class="hero">
      <h1>Android 设置页动作核对 Harness</h1>
      <p>这个页面用来验证 Android WebView 注入 runtime 的设置面板、动作核对、复制结果，以及当前页按钮/扫码动作执行。</p>
    </div>

    <div class="toolbar">
      <button type="button" id="open-settings">打开客户端设置</button>
      <button type="button" class="secondary" id="trigger-scan">模拟扫码事件</button>
    </div>

    <div class="grid">
      <section class="card">
        <h2>单字段场景</h2>
        <div class="field">
          <label for="serial-input">流水号</label>
          <input id="serial-input" name="serial" placeholder="等待 scan 动作写入">
        </div>
        <div class="field">
          <label for="notes-input">备注</label>
          <textarea id="notes-input" name="notes" placeholder="按钮动作会把固定值写到这里"></textarea>
        </div>
        <div class="btn-row">
          <button type="button" data-kh-action="fill-note">触发按钮填充</button>
          <button type="button" class="secondary" data-kh-action="confirm-submit">确认提交</button>
          <button type="button" class="secondary" data-kh-action="print-preview">触发打印预览</button>
        </div>
        <div class="status" id="button-fill-status">等待按钮动作</div>
      </section>

      <section class="card">
        <h2>打印字段场景</h2>
        <div class="field">
          <label for="barcode-input">barcode_content</label>
          <input id="barcode-input" name="barcode_content" value="BC-AND-0001">
        </div>
        <div class="field">
          <label for="print-content">print_content</label>
          <textarea id="print-content" name="print_content">Android 标签内容 A / B / C</textarea>
        </div>
        <div class="field">
          <label>表格预览</label>
          <table class="table">
            <thead>
              <tr><th>条码</th><th>文案</th></tr>
            </thead>
            <tbody>
              <tr><td>TB-A1001</td><td>第一行标签</td></tr>
              <tr><td>TB-A1002</td><td>第二行标签</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>

  <script>
    window.APP_VERSION_NAME = "1.2.17-harness";
    window.APP_VERSION_CODE = "26";
    window.BUILD_TIME = "2026-07-06T00:00:00+08:00";
    window.__khClientRuntime = {{
      defaultServerBase: window.location.origin,
      defaultUpdateBase: window.location.origin,
      pageActionsApi: "/api/test/page-actions"
    }};
    window.Capacitor = {{
      Plugins: {{}}
    }};
    const savedConfig = {{
      serverBase: window.location.origin,
      updateBase: window.location.origin,
      paperType: "thermal",
      layoutPreset: "standard",
      injectionMode: "aggressive",
      enableFloatingLogs: true,
      enableVerboseLogs: true,
      enableNetworkHeaderPatch: true,
      enableHistoryPatch: true,
      enableStoragePatch: true,
      enableUiReadyObserver: true,
      enableActionObserver: true,
      enableRuntimeReuse: true
    }};
    window.ClientConfigPlugin = {{
      getConfig() {{
        return Promise.resolve({{ ...savedConfig }});
      }},
      saveConfig(next) {{
        Object.assign(savedConfig, next || {{}});
        return Promise.resolve({{ ...savedConfig }});
      }},
      restartApp() {{
        window.__restartRequested = true;
        return Promise.resolve(true);
      }}
    }};
    window.Capacitor.Plugins.ClientConfigPlugin = window.ClientConfigPlugin;
    window.UpdatePlugin = {{
      getVersionInfo() {{
        return Promise.resolve({{ versionCode: 26, versionName: "1.2.17" }});
      }},
      downloadAndInstallApk() {{
        return Promise.resolve(true);
      }}
    }};
    window.Capacitor.Plugins.UpdatePlugin = window.UpdatePlugin;
    window.KaihangNativeBridge = {{
      reportPageReadyState(state, detail) {{
        window.__lastReadyState = {{ state, detail }};
      }},
      setScanActionEnabled(enabled) {{
        window.__scanActionEnabled = !!enabled;
      }},
      startScan() {{
        window.dispatchEvent(new CustomEvent("kh:scan", {{ detail: {{ value: "SCAN-BRIDGE-001" }} }}));
        return true;
      }},
      stopScan() {{
        return true;
      }},
      onScanCompleted() {{
        window.__scanCompleted = true;
      }},
      connectPrinter() {{
        return true;
      }},
      prepareToPrintLabel() {{
        return true;
      }},
      printLabel() {{
        return true;
      }}
    }};
    window.__mockActionCatalog = {sample_actions_json};
    window.fetch = (input) => {{
      const url = String(input || "");
      if (url.includes("/api/test/page-actions")) {{
        return Promise.resolve({{
          ok: true,
          json: () => Promise.resolve({{ data: window.__mockActionCatalog }})
        }});
      }}
      if (url.includes("/app-updates/versions.json")) {{
        return Promise.resolve({{
          ok: true,
          json: () => Promise.resolve({{
            currentVersionCode: 26,
            currentVersionFile: "version-26.json"
          }})
        }});
      }}
      if (url.includes("/app-updates/version-26.json")) {{
        return Promise.resolve({{
          ok: true,
          json: () => Promise.resolve({{
            versionCode: 26,
            versionName: "1.2.17"
          }})
        }});
      }}
      return Promise.reject(new Error("unexpected fetch: " + url));
    }};
    window.localStorage.setItem("NOCOBASE_TOKEN", "mock-token");
    window.localStorage.setItem("NOCOBASE_AUTH", "basic");
    window.localStorage.setItem("NOCOBASE_ROLE", "root");
    window.history.replaceState(null, "", "/admin/android-diagnostic-harness");
    document.addEventListener("click", (event) => {{
      const fillButton = event.target.closest('button[data-kh-action="fill-note"]');
      if (fillButton) {{
        setTimeout(() => {{
          const value = document.querySelector('textarea[name="notes"]').value.trim();
          document.getElementById("button-fill-status").textContent = value ? ("已填充: " + value) : "按钮动作未生效";
        }}, 40);
      }}
      const openSettings = event.target.closest("#open-settings");
      if (openSettings && window.__khClientRuntime && window.__khClientRuntime.openSettingsPanel) {{
        window.__khClientRuntime.openSettingsPanel();
      }}
      const scanButton = event.target.closest("#trigger-scan");
      if (scanButton) {{
        window.dispatchEvent(new CustomEvent("kh:scan", {{ detail: {{ value: "SCAN-AND-2026-01" }} }}));
      }}
    }}, true);
  </script>
  <script>
{runtime_script}
  </script>
</body>
</html>
"""


def build_runtime_script() -> str:
    text = SOURCE.read_text(encoding="utf-8")
    parts = [
        json.loads(f'"{match}"')
        for match in re.findall(r'script\.append\("((?:[^"\\]|\\.)*)"\);', text)
    ]
    return "\n".join(parts)


def main() -> None:
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(
        HTML_TEMPLATE.format(
            runtime_script=build_runtime_script(),
            sample_actions_json=json.dumps(SAMPLE_ACTIONS, ensure_ascii=False),
        ),
        encoding="utf-8",
    )
    print(TARGET)


if __name__ == "__main__":
    main()
