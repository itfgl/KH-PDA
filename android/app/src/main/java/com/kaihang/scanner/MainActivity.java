package com.kaihang.scanner;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import com.kaihang.scanner.plugins.KaihangNfcPlugin;
import com.kaihang.scanner.plugins.PrintPlugin;
import com.kaihang.scanner.plugins.ScanPlugin;
import com.kaihang.scanner.plugins.UpdatePlugin;

public class MainActivity extends BridgeActivity {
    private static final String NOCOBASE_STORAGE_PREFIX = "NOCOBASE_";
    private static final String DEFAULT_STORAGE_APP_NAME = "main";
    private static final String DEFAULT_PAGE_ACTIONS_API_PATH = "/api/scanner_page_binding_actions:list?pageSize=200";

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
        String script = buildClientRuntimeScript(view.getUrl());
        view.evaluateJavascript(script, null);
    }

    private String buildClientRuntimeScript(String currentUrl) {
        Uri uri = null;
        try {
            uri = Uri.parse(currentUrl == null ? "" : currentUrl);
        } catch (Exception ignored) {}

        String khToken = uri != null ? safe(uri.getQueryParameter("kh_token")) : "";
        String khAuth = uri != null ? safe(uri.getQueryParameter("kh_auth")) : "";
        String khRole = uri != null ? safe(uri.getQueryParameter("kh_role")) : "";
        String khApp = uri != null ? safe(uri.getQueryParameter("kh_app")) : DEFAULT_STORAGE_APP_NAME;
        String khPaper = uri != null ? safe(uri.getQueryParameter("kh_paper")) : "";
        String khLayout = uri != null ? safe(uri.getQueryParameter("kh_layout")) : "";
        String redirect = uri != null ? safe(uri.getQueryParameter("redirect")) : "";
        boolean shouldBootstrap = uri != null
            && !khToken.isEmpty()
            && !redirect.isEmpty();

        StringBuilder script = new StringBuilder();
        script.append("(function(){");
        script.append("var h='X-Client-Type',v='capacitor';");
        script.append("var kh=window.__khClientRuntime||(window.__khClientRuntime={});");
        script.append("kh.pageActionsApi=").append(js(DEFAULT_PAGE_ACTIONS_API_PATH)).append(";");
        script.append("kh.paperTypeStorageKey='NOCOBASE_PAPER_TYPE';");
        script.append("kh.layoutPresetStorageKey='NOCOBASE_LAYOUT_PRESET';");
        script.append("kh.logStorageKey='KH_FLOATING_LOGS';");
        script.append("kh.appendFloatingLog=function(text,type){if(!kh._logBody)return;var line=document.createElement('div');line.className='kh-log-line kh-'+(type||'plain');line.textContent=text;kh._logBody.appendChild(line);kh._logBody.scrollTop=kh._logBody.scrollHeight;};");
        script.append("kh.clearFloatingLogs=function(){try{window.localStorage&&window.localStorage.removeItem(kh.logStorageKey);}catch(e){}if(kh._logBody)kh._logBody.innerHTML='';};");
        script.append("kh.ensureFloatingLogger=function(){if(document.getElementById('log-fab')||document.getElementById('kh-log-fab'))return;var mount=function(){if(document.getElementById('log-fab')||document.getElementById('kh-log-fab')||!document.body)return;if(!document.getElementById('kh-log-style')&&document.head){var style=document.createElement('style');style.id='kh-log-style';style.textContent='.kh-log-fab{position:fixed;right:18px;bottom:22px;z-index:2147483000;width:54px;height:54px;border:none;border-radius:999px;background:#007aff;color:#fff;font-size:14px;font-weight:700;box-shadow:0 8px 24px rgba(0,0,0,.22)}.kh-log-overlay{position:fixed;inset:0;z-index:2147483001;background:rgba(28,28,30,.32);display:none;align-items:flex-end;justify-content:stretch;padding:16px}.kh-log-panel{width:100%;max-width:520px;margin:0 auto;background:#fff;border-radius:16px;padding:14px;box-shadow:0 12px 32px rgba(0,0,0,.2)}.kh-log-head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:10px}.kh-log-title{font-size:12px;font-weight:700;color:#8e8e93;letter-spacing:.4px;text-transform:uppercase}.kh-log-actions{display:flex;gap:6px}.kh-log-btn{border:none;background:#e5e5ea;color:#1c1c1e;border-radius:7px;padding:6px 10px;font-size:12px;font-weight:700}.kh-log-body{background:#1c1c1e;border-radius:10px;padding:10px;max-height:min(55vh,420px);overflow-y:auto}.kh-log-line{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-all;border-bottom:1px solid #2c2c2e}.kh-log-line.kh-info{color:#64d2ff}.kh-log-line.kh-ok{color:#30d158}.kh-log-line.kh-err{color:#ff453a}.kh-log-line.kh-warn{color:#ffd60a}.kh-log-line.kh-plain{color:#ebebf5}';document.head.appendChild(style);}var fab=document.createElement('button');fab.id='kh-log-fab';fab.type='button';fab.className='kh-log-fab';fab.textContent='日志';var overlay=document.createElement('div');overlay.id='kh-log-overlay';overlay.className='kh-log-overlay';overlay.innerHTML='<div class=\"kh-log-panel\"><div class=\"kh-log-head\"><span class=\"kh-log-title\">运行日志</span><div class=\"kh-log-actions\"><button type=\"button\" class=\"kh-log-btn\" id=\"kh-log-clear\">清空</button><button type=\"button\" class=\"kh-log-btn\" id=\"kh-log-close\">关闭</button></div></div><div class=\"kh-log-body\" id=\"kh-log-body\"></div></div>';document.body.appendChild(fab);document.body.appendChild(overlay);kh._logOverlay=overlay;kh._logBody=overlay.querySelector('#kh-log-body');fab.addEventListener('click',function(){overlay.style.display='flex';});overlay.addEventListener('click',function(evt){if(evt.target===overlay)overlay.style.display='none';});overlay.querySelector('#kh-log-close').addEventListener('click',function(){overlay.style.display='none';});overlay.querySelector('#kh-log-clear').addEventListener('click',function(){kh.clearFloatingLogs();});var saved=[];try{saved=JSON.parse((window.localStorage&&window.localStorage.getItem(kh.logStorageKey))||'[]');}catch(e){saved=[];}if(Array.isArray(saved)){saved.forEach(function(item){if(item&&typeof item==='object')kh.appendFloatingLog(item.text||'',item.type||'plain');else if(item)kh.appendFloatingLog(String(item),'plain');});}};if(document.body)mount();else window.addEventListener('DOMContentLoaded',mount,{once:true});};");
        script.append("kh.toggleFloatingLog=function(show){kh.ensureFloatingLogger();if(kh._logOverlay)kh._logOverlay.style.display=show?'flex':'none';};");
        script.append("kh.pushLog=function(msg,type){var text='['+new Date().toTimeString().slice(0,8)+'] '+String(msg||'');try{var saved=JSON.parse((window.localStorage&&window.localStorage.getItem(kh.logStorageKey))||'[]');if(!Array.isArray(saved))saved=[];saved.push({text:text,type:type||'plain'});if(saved.length>200)saved=saved.slice(saved.length-200);window.localStorage&&window.localStorage.setItem(kh.logStorageKey,JSON.stringify(saved));}catch(e){}kh.ensureFloatingLogger();kh.appendFloatingLog(text,type||'plain');};");
        script.append("kh.ensureFloatingLogger();if(!window.log){window.log=function(msg,type){kh.pushLog(msg,type||'plain');};}if(!window.__khRuntimeErrorHooked){window.__khRuntimeErrorHooked=true;window.addEventListener('error',function(e){kh.pushLog('JS ERROR: '+(e&&e.message||'unknown'),'err');});window.addEventListener('unhandledrejection',function(e){var reason=e&&e.reason;kh.pushLog('UNHANDLED: '+((reason&&reason.message)||reason||'unknown'),'err');});}");
        script.append("kh.getScanPlugin=function(){return window.ScanPlugin||(window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.ScanPlugin)||null;};");
        script.append("kh.ensureScanBridge=function(){if(kh._scanBridgeReady)return kh._scanBridgeReady;var plugin=kh.getScanPlugin();if(!plugin||!plugin.addListener){kh._scanBridgeReady=Promise.reject(new Error('ScanPlugin unavailable'));return kh._scanBridgeReady;}kh._scanBridgeReady=Promise.resolve(plugin.addListener('scanResult',function(evt){var value=evt&&evt.value?String(evt.value):'';if(!value)return;kh.pushLog('收到扫码: '+value,'ok');var handled=kh.execTriggeredActions&&kh.execTriggeredActions('scan',value);if(!handled){kh.injectValue('',value,false);}})).then(function(){kh.pushLog('扫码桥已就绪','info');return true;}).catch(function(err){kh.pushLog('扫码桥初始化失败: '+String(err&&err.message||err||'unknown'),'err');throw err;});return kh._scanBridgeReady;};");
        script.append("kh.startGlobalScan=function(){var plugin=kh.getScanPlugin();if(!plugin||!plugin.startScan){kh.pushLog('扫码不可用: ScanPlugin unavailable','err');return Promise.reject(new Error('ScanPlugin unavailable'));}return kh.ensureScanBridge().catch(function(){return true;}).then(function(){kh.pushLog('手动触发扫码','info');return Promise.resolve(plugin.startScan()).catch(function(err){kh.pushLog('触发扫码失败: '+String(err&&err.message||err||'unknown'),'err');throw err;});});};");
        script.append("kh.ensureGlobalScanButton=function(){var mount=function(){if(document.getElementById('kh-scan-fab')||!document.body)return;var btn=document.createElement('button');btn.id='kh-scan-fab';btn.type='button';btn.textContent='扫码';btn.style.cssText='position:fixed;right:18px;bottom:86px;z-index:2147483000;width:54px;height:54px;border:none;border-radius:999px;background:#34c759;color:#fff;font-size:14px;font-weight:700;box-shadow:0 8px 24px rgba(0,0,0,.22)';btn.addEventListener('click',function(){kh.startGlobalScan();});document.body.appendChild(btn);};if(document.body)mount();else window.addEventListener('DOMContentLoaded',mount,{once:true});};");
        script.append("kh.ensureGlobalScanButton();kh.ensureScanBridge().catch(function(){return null;});kh.pushLog('页面注入完成: '+window.location.href,'plain');");
        script.append("var patchFetch=function(){var of=window.fetch;if(!of||of.__khWrapped)return;var wf=function(r,i){i=i||{};var hs=new Headers(i.headers||(r&&r.headers)||{});if(!hs.has(h))hs.set(h,v);i.headers=hs;return of.call(this,r,i);};wf.__khWrapped=true;window.fetch=wf;};");
        script.append("var patchXhr=function(){if(XMLHttpRequest.prototype.__khWrapped)return;var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send,osr=XMLHttpRequest.prototype.setRequestHeader;");
        script.append("XMLHttpRequest.prototype.open=function(){this.__khSet=false;return oo.apply(this,arguments);};");
        script.append("XMLHttpRequest.prototype.setRequestHeader=function(n,val){if(String(n).toLowerCase()===h.toLowerCase())this.__khSet=true;return osr.apply(this,arguments);};");
        script.append("XMLHttpRequest.prototype.send=function(b){if(!this.__khSet){osr.call(this,h,v);this.__khSet=true;}return os.call(this,b);};");
        script.append("XMLHttpRequest.prototype.__khWrapped=true;};");
        script.append("kh.normalizeBool=function(value,def){if(value===undefined||value===null)return !!def;if(typeof value==='boolean')return value;if(typeof value==='number')return !!value;var text=String(value).trim().toLowerCase();if(['1','true','yes','y','on'].indexOf(text)>=0)return true;if(['0','false','no','n','off'].indexOf(text)>=0)return false;return !!def;};");
        script.append("kh.pickTarget=function(selector){var isEditable=function(el){if(!el)return false;if(el.isContentEditable)return true;var tag=(el.tagName||'').toLowerCase();if(tag==='textarea')return true;if(tag!=='input')return false;var type=(el.type||'text').toLowerCase();return ['button','submit','reset','checkbox','radio','file','image','hidden'].indexOf(type)<0;};var isVisible=function(el){if(!el)return false;var style=window.getComputedStyle(el);return style.display!=='none'&&style.visibility!=='hidden'&&!el.disabled;};if(selector){var nodes=Array.from(document.querySelectorAll(selector));for(var i=0;i<nodes.length;i++){if(isEditable(nodes[i])&&isVisible(nodes[i]))return nodes[i];}}var active=document.activeElement;if(isEditable(active)&&isVisible(active))return active;var all=Array.from(document.querySelectorAll('input,textarea,[contenteditable=\"true\"]'));for(var j=0;j<all.length;j++){if(isEditable(all[j])&&isVisible(all[j]))return all[j];}return null;};");
        script.append("kh.injectValue=function(selector,value,autoPressEnter){var target=kh.pickTarget(selector);if(!target)return false;target.focus&&target.focus();target.click&&target.click();if(target.isContentEditable){target.textContent=value;}else{var proto=(target.tagName||'').toLowerCase()==='textarea'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var desc=Object.getOwnPropertyDescriptor(proto,'value');if(desc&&desc.set){desc.set.call(target,value);}else{target.value=value;}}target.dispatchEvent(new Event('input',{bubbles:true}));target.dispatchEvent(new Event('change',{bubbles:true}));if(autoPressEnter){try{target.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));target.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}}return true;};");
        script.append("kh.clickSelector=function(selector){if(!selector)return false;var target=document.querySelector(selector);if(!target)return false;target.focus&&target.focus();target.click&&target.click();return true;};");
        script.append("kh.normalizeAction=function(item,index){if(!item||typeof item!=='object')return null;var options=item.options;if(typeof options==='string'&&options.trim()){try{options=JSON.parse(options);}catch(e){options={};}}if(!options||typeof options!=='object')options={};var triggerType=String(item.trigger_type||item.triggerType||item.trigger||item.event||options.trigger_type||options.trigger||'').trim().toLowerCase();var actionType=String(item.action_type||item.actionType||item.action||item.type||options.action_type||options.action||'').trim().toLowerCase();if(!triggerType||!actionType)return null;var sortOrder=parseInt(item.sort||item.sortOrder||item.order||options.sort||options.order||index,10);if(Number.isNaN(sortOrder))sortOrder=index;var delayMs=parseInt(item.delay_ms||item.delayMs||options.delay_ms||0,10);if(Number.isNaN(delayMs)||delayMs<0)delayMs=0;return {id:String(item.id||item.key||('page-action-'+index)),enabled:kh.normalizeBool(item.enabled,true),roleName:String(item.role_name||item.roleName||item.role||options.role_name||options.role||'').trim(),pagePath:String(item.page_path||item.pagePath||item.page||item.path||item.page_url||item.pageUrl||options.page_path||options.page||'').trim(),triggerType:triggerType,triggerSelector:String(item.trigger_selector||item.triggerSelector||options.trigger_selector||'').trim(),actionType:actionType,targetSelector:String(item.target_selector||item.targetSelector||options.target_selector||'').trim(),value:String(item.value||options.value||'').trim(),autoPressEnter:kh.normalizeBool(item.auto_press_enter!==undefined?item.auto_press_enter:options.auto_press_enter,false),delayMs:delayMs,sortOrder:sortOrder,options:options,raw:item};};");
        script.append("kh.roleMatch=function(actionRole,currentRole){var role=String(actionRole||'').trim().toLowerCase();if(!role)return true;var current=String(currentRole||'').trim().toLowerCase();var parts=role.split(/[;,|]/).map(function(v){return v.trim();}).filter(Boolean);return parts.indexOf(current)>=0;};");
        script.append("kh.pageMatch=function(pagePath,currentUrl){var path=String(pagePath||'').trim();if(!path)return true;if(/^https?:\\/\\//i.test(path))return String(currentUrl||'').indexOf(path)===0;var url;try{url=new URL(currentUrl||window.location.href);}catch(e){url=window.location;}var currentPath=url.pathname||'/';var expected=path.charAt(0)==='/'?path:('/'+path);return currentPath.indexOf(expected)===0;};");
        script.append("kh.readSelector=function(selector){if(!selector)return '';var el=document.querySelector(selector);if(!el)return '';var value=('value' in el&&el.value!==undefined&&el.value!==null)?String(el.value).trim():'';if(value)return value;return String(el.textContent||el.innerText||'').trim();};");
        script.append("kh.applyTemplate=function(value,scanValue){return String(value||'').replace(/\\{\\{\\s*scan\\s*\\}\\}/gi,String(scanValue||''));};");
        script.append("kh.getStoredValue=function(key){var storages=[window.localStorage,window.sessionStorage].filter(Boolean);for(var i=0;i<storages.length;i++){var value=storages[i].getItem(key);if(value!==null&&value!==undefined&&String(value).trim()!=='')return String(value).trim();}return '';};");
        script.append("kh.normalizePaperType=function(value){return String(value||'').trim().toLowerCase()==='black_mark'?'black_mark':'thermal';};");
        script.append("kh.normalizeLayoutPreset=function(value){var raw=String(value||'').trim().toLowerCase();return ['compact','large'].indexOf(raw)>=0?raw:'standard';};");
        script.append("kh.resolveActionField=function(action,name,scanValue){var options=action&&action.options||{};var raw=action&&action.raw||{};var direct=options[name];if((direct===undefined||direct===null||String(direct).trim()==='')&&raw[name]!==undefined)direct=raw[name];if(direct!==undefined&&direct!==null&&String(direct).trim()!=='')return kh.applyTemplate(String(direct),scanValue);var selector=options[name+'_selector']||options[name+'Selector']||raw[name+'_selector']||raw[name+'Selector']||'';if(selector){var selected=kh.readSelector(selector);if(selected)return kh.applyTemplate(selected,scanValue);}if(name==='barcode_value'&&scanValue)return String(scanValue).trim();if(name==='barcode_value'&&action&&action.value)return kh.applyTemplate(action.value,scanValue);return '';};");
        script.append("kh.resolvePrintConfig=function(action){var options=action&&action.options||{};var raw=action&&action.raw||{};var paperType=options.paper_type||options.paperType||raw.paper_type||raw.paperType||kh.getStoredValue(kh.paperTypeStorageKey)||'thermal';var layoutPreset=options.layout_preset||options.layoutPreset||raw.layout_preset||raw.layoutPreset||kh.getStoredValue(kh.layoutPresetStorageKey)||'standard';return {paperType:kh.normalizePaperType(paperType),layoutPreset:kh.normalizeLayoutPreset(layoutPreset)};};");
        script.append("kh.getPrintPlugin=function(){return window.PrintPlugin||(window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.PrintPlugin)||null;};");
        script.append("kh.waitPrintStatus=function(plugin,wanted,timeoutMs){var ERRORS=['NO_PAPER','PRINTER_CLOSED','SEND_DATA_FAILED','PRINT_FAILED','BLACK_FLAG_NOT_FOUND','PREPARE_LABEL_NO_PAPER','PREPARE_LABEL_BLACK_FLAG_NOT_FOUND','PREPARE_LABEL_FAILED','PREPARE_LABEL_PRINTER_CLOSED','PREPARE_LABEL_SEND_DATA_FAILED'];return new Promise(function(resolve,reject){var done=false;var sub=null;var timer=setTimeout(function(){finish(new Error('print status timeout: '+wanted));},timeoutMs||15000);var finish=function(err){if(done)return;done=true;clearTimeout(timer);try{sub&&sub.remove&&sub.remove();}catch(e){}if(err)reject(err);else resolve();};Promise.resolve(plugin.addListener('printStatus',function(evt){var status=evt&&evt.status;if(!status)return;if(status===wanted){finish();}else if(ERRORS.indexOf(status)>=0){finish(new Error(status));}})).then(function(handle){sub=handle;}).catch(finish);});};");
        script.append("kh.ensurePrintConnected=function(plugin){kh._printConnectPromise=kh._printConnectPromise||Promise.resolve(plugin.connect&&plugin.connect()).catch(function(){return null;});return kh._printConnectPromise;};");
        script.append("kh.runPrintAction=function(action,scanValue){var plugin=kh.getPrintPlugin();if(!plugin)return Promise.reject(new Error('PrintPlugin unavailable'));var actionType=String(action.actionType||'').toLowerCase();if(actionType==='print_label'||actionType==='print_batch_label'){var printConfig=kh.resolvePrintConfig(action);var payload={barcodeValue:kh.resolveActionField(action,'barcode_value',scanValue),qrCodeValue:kh.resolveActionField(action,'qrcode_value',scanValue),textValue:kh.resolveActionField(action,'text_value',scanValue),paperType:printConfig.paperType,layoutPreset:printConfig.layoutPreset};if(!payload.barcodeValue&&!payload.qrCodeValue&&!String(payload.textValue||'').trim())return Promise.reject(new Error('print action missing barcode/qrcode/text'));kh.pushLog('开始打印动作: '+(action.id||action.actionType)+' ['+payload.paperType+'/'+payload.layoutPreset+']','info');return kh.ensurePrintConnected(plugin).then(function(){if(payload.paperType==='black_mark'&&plugin.prepareToPrintLabel){return Promise.resolve(plugin.prepareToPrintLabel()).catch(function(){return null;});}return null;}).then(function(){var waitDone=kh.waitPrintStatus(plugin,'PRINT_OK',15000);return Promise.resolve(plugin.printLabel(payload)).then(function(){return waitDone;});});}return Promise.reject(new Error('unsupported print action: '+actionType));};");
        script.append("kh.execAction=function(action,scanValue){if(!action||!action.enabled)return false;var runner=function(){if(action.actionType==='fill_input'||action.actionType==='fill'||action.actionType==='scan_fill'||action.actionType==='input'){return kh.injectValue(action.targetSelector,scanValue||action.value||'',!!action.autoPressEnter);}if(action.actionType==='click'||action.actionType==='tap'){return kh.clickSelector(action.targetSelector);}if(action.actionType==='print_label'||action.actionType==='print_batch_label'){kh.runPrintAction(action,scanValue||'').then(function(){kh.pushLog('打印动作成功: '+(action.id||action.actionType),'ok');}).catch(function(err){kh.pushLog('打印动作失败: '+String(err&&err.message||err||'print failed'),'err');window.__khLastActionError=String(err&&err.message||err||'print failed');});return true;}if(action.actionType==='noop'||action.actionType==='none'){return true;}return false;};if(action.delayMs>0){setTimeout(runner,action.delayMs);return true;}return runner();};");
        script.append("kh.execTriggeredActions=function(triggerType,scanValue){var grouped=window.__khPageActions||{};var actions=Array.isArray(grouped[triggerType])?grouped[triggerType]:[];if(!actions.length)return false;actions.slice().sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);}).forEach(function(action){kh.execAction(action,scanValue||'');});return true;};");
        script.append("kh.attachButtonActions=function(){if(window.__khButtonActionsBound)return;document.addEventListener('click',function(event){var actions=(window.__khPageActions&&window.__khPageActions.button)||[];for(var i=0;i<actions.length;i++){var action=actions[i];if(!action.triggerSelector)continue;var target=event.target&&event.target.closest?event.target.closest(action.triggerSelector):null;if(!target)continue;event.preventDefault();event.stopPropagation();kh.execAction(action,'');return;}},true);window.__khButtonActionsBound=true;};");
        script.append("kh.loadPageActions=function(){var storages=[window.localStorage,window.sessionStorage].filter(Boolean);var getStored=function(key){for(var i=0;i<storages.length;i++){var value=storages[i].getItem(key);if(value)return value;}return '';};var token=getStored('NOCOBASE_TOKEN')||getStored('NOCOBASE_MAIN_TOKEN');var auth=getStored('NOCOBASE_AUTH')||getStored('NOCOBASE_MAIN_AUTH')||'basic';var role=getStored('NOCOBASE_ROLE')||getStored('NOCOBASE_MAIN_ROLE')||'';if(!token||!window.fetch){window.__khPageActions={scan:[],button:[]};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();kh.pushLog('未检测到页面 token，跳过页面动作加载','warn');return Promise.resolve();}var requestUrl=new URL(kh.pageActionsApi,window.location.origin).toString();return window.fetch(requestUrl,{headers:{'Authorization':'Bearer '+token,'X-Authenticator':auth,'X-Requested-With':'XMLHttpRequest'}}).then(function(res){if(!res.ok)throw new Error('page actions '+res.status);return res.json();}).then(function(payload){var data=(payload&&payload.data!==undefined)?payload.data:payload;var items=Array.isArray(data)?data:(Array.isArray(data&&data.items)?data.items:(Array.isArray(data&&data.rows)?data.rows:[]));var scan=[];var button=[];for(var i=0;i<items.length;i++){var action=kh.normalizeAction(items[i],i+1);if(!action||!action.enabled)continue;if(!kh.roleMatch(action.roleName,role))continue;if(!kh.pageMatch(action.pagePath,window.location.href))continue;if(action.triggerType==='button')button.push(action);else if(action.triggerType==='scan')scan.push(action);}scan.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});button.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});window.__khPageActions={scan:scan,button:button};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();kh.pushLog('页面动作已加载: scan='+scan.length+', button='+button.length+', role='+(role||'<empty>'),'info');}).catch(function(err){kh.pushLog('页面动作加载失败: '+String(err&&err.message||err||'unknown'),'err');window.__khPageActions={scan:[],button:[]};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();});};");
        script.append("patchFetch();patchXhr();");
        if (shouldBootstrap) {
            script.append("try{");
            script.append("var storages=[window.localStorage,window.sessionStorage].filter(Boolean);");
            script.append("var setValue=function(storage,key,val){if(val===null||val===undefined||val===''){storage.removeItem(key);}else{storage.setItem(key,val);}};");
            script.append("var token=").append(js(khToken)).append(";");
            script.append("var auth=").append(js(khAuth.isEmpty() ? "basic" : khAuth)).append(";");
            script.append("var role=").append(js(khRole)).append(";");
            script.append("var app=").append(js(khApp)).append(";");
            script.append("var paper=").append(js(khPaper)).append(";");
            script.append("var layout=").append(js(khLayout)).append(";");
            script.append("var redirect=").append(js(redirect)).append(";");
            script.append("var prefixes=['").append(NOCOBASE_STORAGE_PREFIX).append("'];");
            script.append("if(app){prefixes.push('").append(NOCOBASE_STORAGE_PREFIX).append("' + app.toUpperCase() + '_');}");
            script.append("kh.pushLog('注入登录态并跳转到业务页: '+redirect,'info');");
            script.append("prefixes.forEach(function(prefix){storages.forEach(function(storage){setValue(storage,prefix+'TOKEN',token);setValue(storage,prefix+'AUTH',auth);setValue(storage,prefix+'ROLE',role);});});");
            script.append("storages.forEach(function(storage){setValue(storage,kh.paperTypeStorageKey,paper);setValue(storage,kh.layoutPresetStorageKey,layout);});");
            script.append("window.location.replace(redirect);");
            script.append("return;");
            script.append("}catch(e){console.error('kh bootstrap failed',e);}");
        }
        script.append("Promise.resolve().then(function(){return kh.loadPageActions();});");
        script.append("})();");
        return script.toString();
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
