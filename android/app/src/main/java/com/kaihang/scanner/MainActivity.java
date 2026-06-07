package com.kaihang.scanner;

import android.content.Intent;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import com.kaihang.scanner.plugins.KaihangNfcPlugin;
import com.kaihang.scanner.plugins.PrintPlugin;
import com.kaihang.scanner.plugins.ScanPlugin;
import com.kaihang.scanner.plugins.UpdatePlugin;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(ScanPlugin.class);
        registerPlugin(PrintPlugin.class);
        registerPlugin(KaihangNfcPlugin.class);
        registerPlugin(UpdatePlugin.class);
        super.onCreate(savedInstanceState);

        // 全局崩溃拦截：将异常信息转发到 JS 日志
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            android.util.Log.e("KaihangCrash", "Uncaught exception on " + thread.getName(), throwable);
            try {
                // 取前 400 字符避免 JS 字符串过长
                String msg = throwable.toString().replace("'", "\\'").replace("\n", " ");
                if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
                final String script = "window.log && window.log('CRASH: " + msg + "', 'err')";
                runOnUiThread(() -> {
                    if (bridge != null) bridge.getWebView().evaluateJavascript(script, null);
                });
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void load() {
        super.load();

        // 所有 fetch/XHR 请求自动附加 X-Client-Type: capacitor 头，便于服务端区分客户端类型
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                injectClientTypeHeader(view);
            }
            @Override
            public void onPageLoaded(WebView view) {
                super.onPageLoaded(view);
                injectClientTypeHeader(view);
            }
        });
    }

    // App 已在前台时收到 NFC Intent，转发给 Capacitor Bridge（@capgo/capacitor-nfc 依赖此回调）
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bridge.onNewIntent(intent);
    }

    private void injectClientTypeHeader(WebView view) {
        view.evaluateJavascript(
            "(function(){" +
            "if(window.__khPatch)return;window.__khPatch=true;" +
            "var h='X-Client-Type',v='capacitor';" +
            "var of=window.fetch;" +
            "if(of){window.fetch=function(r,i){i=i||{};var hs=new Headers(i.headers||(r&&r.headers)||{});if(!hs.has(h))hs.set(h,v);i.headers=hs;return of.call(this,r,i);}}" +
            "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send,osr=XMLHttpRequest.prototype.setRequestHeader;" +
            "XMLHttpRequest.prototype.open=function(){this.__khSet=false;return oo.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.setRequestHeader=function(n,val){if(String(n).toLowerCase()===h.toLowerCase())this.__khSet=true;return osr.apply(this,arguments);};" +
            "XMLHttpRequest.prototype.send=function(b){if(!this.__khSet){osr.call(this,h,v);this.__khSet=true;}return os.call(this,b);};" +
            "})();",
            null
        );
    }
}
