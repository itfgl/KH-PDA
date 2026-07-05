import fs from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import playwrightPkg from "../../my-nocobase-app/node_modules/playwright/index.js";

const { chromium } = playwrightPkg;

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const root = path.resolve(__dirname, "..");
const harnessPath = path.join(root, "ui", "settings-diagnostic-harness.html");
const screenshotDir = path.resolve(root, "..", "output", "playwright");
const screenshotPath = path.join(screenshotDir, "android-settings-diagnostic-harness.png");

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function main() {
  await fs.mkdir(screenshotDir, { recursive: true });
  const harnessHtml = await fs.readFile(harnessPath, "utf-8");
  const server = http.createServer((req, res) => {
    if (req.url === "/admin/android-diagnostic-harness" || req.url === "/") {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      res.end(harnessHtml);
      return;
    }
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("Not Found");
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const harnessUrl = `http://127.0.0.1:${address.port}/admin/android-diagnostic-harness`;

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1800 } });
  const consoleLogs = [];

  try {
    page.on("console", (message) => {
      consoleLogs.push(`[${message.type()}] ${message.text()}`);
    });
    page.on("pageerror", (error) => {
      consoleLogs.push(`[pageerror] ${error?.stack || error?.message || String(error)}`);
    });

    await page.goto(harnessUrl);
    await page.waitForFunction(() => !!window.__khClientRuntime);
    try {
      await page.waitForFunction(() => window.__khClientRuntime.bootState === "ready", { timeout: 10000 });
    } catch (error) {
      const runtimeState = await page.evaluate(() => ({
        bootState: window.__khClientRuntime?.bootState,
        pageApplyState: window.__khClientRuntime?.pageApplyState,
        lastError: window.__khLastActionError || null,
        hasSettingsOverlay: !!document.querySelector("#kh-settings-overlay"),
      }));
      throw new Error(`runtime not ready: ${JSON.stringify(runtimeState)}\n${consoleLogs.join("\n")}`);
    }

    await page.click('button[data-kh-action="fill-note"]', { force: true });
    await page.waitForFunction(() => {
      const textarea = document.querySelector("textarea[name='notes']");
      return !!textarea && textarea.value === "来自 Android 按钮动作";
    });

    await page.evaluate(() => {
      window.dispatchEvent(new CustomEvent("kh:scan", { detail: { value: "SCAN-AND-2026-01" } }));
    });
    await page.waitForFunction(() => {
      const input = document.querySelector("input[name='serial']");
      return !!input && input.value === "SCAN-AND-2026-01";
    });

    await page.evaluate(() => {
      window.__khClientRuntime.openSettingsPanel();
    });
    await page.waitForFunction(() => {
      const overlay = document.querySelector("#kh-settings-overlay");
      return !!overlay && getComputedStyle(overlay).display !== "none";
    });

    await page.click("#kh-settings-diagnose-btn", { force: true });
    try {
      await page.waitForFunction(() => !!window.__khClientRuntime._lastSettingsDiagnosticReport, { timeout: 10000 });
    } catch (error) {
      const diagnosticsState = await page.evaluate(() => ({
        statusText: document.querySelector("#kh-settings-status")?.textContent || "",
        diagnosticsText: document.querySelector("#kh-settings-diagnostics")?.textContent || "",
        report: window.__khClientRuntime?._lastSettingsDiagnosticReport || null,
      }));
      throw new Error(`diagnostics not ready: ${JSON.stringify(diagnosticsState)}\n${consoleLogs.join("\n")}`);
    }

    const diagText = await page.locator("#kh-settings-diagnostics").innerText();
    assert(diagText.trim().length > 20, `诊断面板内容过短: ${diagText}`);

    const copyResult = await page.evaluate(async () => {
      return await window.__khClientRuntime.copySettingsDiagnostics();
    });
    assert(copyResult && copyResult.ok, `复制诊断结果失败: ${JSON.stringify(copyResult)}`);

    const report = await page.evaluate(() => window.__khClientRuntime._lastSettingsDiagnosticReport);
    assert(report?.catalog?.total === 4, `动作总数不符合预期: ${JSON.stringify(report?.catalog)}`);
    assert(report?.catalog?.pageMatched === 4, `当前页命中数不符合预期: ${JSON.stringify(report?.catalog)}`);
    assert(report?.catalog?.pageRunnable === 2, `当前页可执行数不符合预期: ${JSON.stringify(report?.catalog)}`);
    assert(report?.summary?.unsupported >= 1, `未识别到打印 unsupported: ${JSON.stringify(report?.summary)}`);
    assert(
      report?.actions?.some((item) => item.id === "print-single-android-1" && item.actionType === "print_batch_label"),
      `缺少打印动作诊断结果: ${JSON.stringify(report?.actions)}`,
    );
    assert(
      report?.actions?.some((item) => item.id === "button-missing-android-1" && item.status === "unavailable"),
      "缺少 trigger_selector 未命中的 unavailable 结果",
    );

    await page.screenshot({ path: screenshotPath, fullPage: true });

    console.log(JSON.stringify({
      ok: true,
      screenshotPath,
      summary: report.summary,
      catalog: report.catalog,
      copyResult,
    }, null, 2));
  } finally {
    await browser.close();
    await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
