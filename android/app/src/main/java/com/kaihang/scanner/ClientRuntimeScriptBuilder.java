package com.kaihang.scanner;

import android.content.Context;
import android.net.Uri;

final class ClientRuntimeScriptBuilder {
    private static final String CORE_ASSET_PATH = "runtime/client-runtime.core.js";
    private static final String PRINT_ASSET_PATH = "runtime/client-runtime.print.js";
    private static final String DIAGNOSTICS_ASSET_PATH = "runtime/client-runtime.diagnostics.js";
    private static final String ACTION_EVENTS_ASSET_PATH = "runtime/client-runtime.action-events.js";
    private static final String NOCOBASE_EVENTS_ASSET_PATH = "runtime/client-runtime.nocobase-events.js";
    private static final String BOOTSTRAP_ASSET_PATH = "runtime/client-runtime.bootstrap.js";

    private ClientRuntimeScriptBuilder() {}

    static String build(
        Context context,
        String currentUrl,
        String buildTime,
        String versionName,
        int versionCode,
        String pageActionsApiPath,
        String defaultServerBase,
        String defaultUpdateBase,
        String nocobaseStoragePrefix,
        String defaultStorageAppName
    ) {
        Uri uri = null;
        try {
            uri = Uri.parse(currentUrl == null ? "" : currentUrl);
        } catch (Exception ignored) {}

        String khToken = uri != null ? safe(uri.getQueryParameter("kh_token")) : "";
        String khAuth = uri != null ? safe(uri.getQueryParameter("kh_auth")) : "";
        String khRole = uri != null ? safe(uri.getQueryParameter("kh_role")) : "";
        String khApp = uri != null ? safe(uri.getQueryParameter("kh_app")) : defaultStorageAppName;
        String khPaper = uri != null ? safe(uri.getQueryParameter("kh_paper")) : "";
        String redirect = uri != null ? safe(uri.getQueryParameter("redirect")) : "";
        boolean shouldBootstrap = uri != null
            && !khToken.isEmpty()
            && !redirect.isEmpty();

        StringBuilder script = new StringBuilder();
        script.append("(function(){window.__khRuntimeValues={");
        script.append("buildTime:").append(js(buildTime)).append(",");
        script.append("versionName:").append(js(versionName)).append(",");
        script.append("versionCode:").append(versionCode).append(",");
        script.append("pageActionsApi:").append(js(pageActionsApiPath)).append(",");
        script.append("defaultServerBase:").append(js(defaultServerBase)).append(",");
        script.append("defaultUpdateBase:").append(js(defaultUpdateBase)).append(",");
        script.append("shouldBootstrap:").append(shouldBootstrap).append(",");
        script.append("khToken:").append(js(khToken)).append(",");
        script.append("khAuth:").append(js(khAuth.isEmpty() ? "basic" : khAuth)).append(",");
        script.append("khRole:").append(js(khRole)).append(",");
        script.append("khApp:").append(js(khApp)).append(",");
        script.append("khPaper:").append(js(khPaper)).append(",");
        script.append("redirect:").append(js(redirect)).append(",");
        script.append("nocobaseStoragePrefix:").append(js(nocobaseStoragePrefix)).append(",");
        script.append("defaultStorageAppName:").append(js(defaultStorageAppName));
        script.append("};})();");
        script.append(readAsset(context, CORE_ASSET_PATH));
        script.append(readAsset(context, PRINT_ASSET_PATH));
        script.append(readAsset(context, DIAGNOSTICS_ASSET_PATH));
        script.append(readAsset(context, ACTION_EVENTS_ASSET_PATH));
        script.append(readAsset(context, NOCOBASE_EVENTS_ASSET_PATH));
        script.append(readAsset(context, BOOTSTRAP_ASSET_PATH));
        return script.toString();
    }

    private static String readAsset(Context context, String assetPath) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required for runtime asset loading");
        }
        try (java.io.InputStream inputStream = context.getAssets().open(assetPath);
             java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load runtime asset: " + assetPath, e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String js(String value) {
        String v = value == null ? "" : value;
        return "'" + v
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "'";
    }
}
