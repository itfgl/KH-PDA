package com.kaihang.scanner;

import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import com.kaihang.scanner.plugins.PrintPlugin;
import com.kaihang.scanner.plugins.ScanPlugin;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(ScanPlugin.class);
        registerPlugin(PrintPlugin.class);
        super.onCreate(savedInstanceState);
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
