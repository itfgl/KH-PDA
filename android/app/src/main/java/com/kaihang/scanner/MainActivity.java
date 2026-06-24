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
    private static final String DEFAULT_PAGE_ACTIONS_API_PATH = "/api/client_page_actions:list?pageSize=200";

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
            && !redirect.isEmpty()
            && uri.getPath() != null
            && uri.getPath().contains("/signin");

        StringBuilder script = new StringBuilder();
        script.append("(function(){");
        script.append("var h='X-Client-Type',v='capacitor';");
        script.append("var kh=window.__khClientRuntime||(window.__khClientRuntime={});");
        script.append("kh.pageActionsApi=").append(js(DEFAULT_PAGE_ACTIONS_API_PATH)).append(";");
        script.append("kh.paperTypeStorageKey='NOCOBASE_PAPER_TYPE';");
        script.append("kh.layoutPresetStorageKey='NOCOBASE_LAYOUT_PRESET';");
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
        script.append("kh.runPrintAction=function(action,scanValue){var plugin=kh.getPrintPlugin();if(!plugin)return Promise.reject(new Error('PrintPlugin unavailable'));var actionType=String(action.actionType||'').toLowerCase();if(actionType==='print_label'||actionType==='print_batch_label'){var printConfig=kh.resolvePrintConfig(action);var payload={barcodeValue:kh.resolveActionField(action,'barcode_value',scanValue),qrCodeValue:kh.resolveActionField(action,'qrcode_value',scanValue),textValue:kh.resolveActionField(action,'text_value',scanValue),paperType:printConfig.paperType,layoutPreset:printConfig.layoutPreset};if(!payload.barcodeValue&&!payload.qrCodeValue&&!String(payload.textValue||'').trim())return Promise.reject(new Error('print action missing barcode/qrcode/text'));return kh.ensurePrintConnected(plugin).then(function(){if(payload.paperType==='black_mark'&&plugin.prepareToPrintLabel){return Promise.resolve(plugin.prepareToPrintLabel()).catch(function(){return null;});}return null;}).then(function(){var waitDone=kh.waitPrintStatus(plugin,'PRINT_OK',15000);return Promise.resolve(plugin.printLabel(payload)).then(function(){return waitDone;});});}return Promise.reject(new Error('unsupported print action: '+actionType));};");
        script.append("kh.execAction=function(action,scanValue){if(!action||!action.enabled)return false;var runner=function(){if(action.actionType==='fill_input'||action.actionType==='fill'||action.actionType==='scan_fill'||action.actionType==='input'){return kh.injectValue(action.targetSelector,scanValue||action.value||'',!!action.autoPressEnter);}if(action.actionType==='click'||action.actionType==='tap'){return kh.clickSelector(action.targetSelector);}if(action.actionType==='print_label'||action.actionType==='print_batch_label'){kh.runPrintAction(action,scanValue||'').then(function(){console.info('kh print action ok',action.id||action.actionType);}).catch(function(err){console.warn('kh print action failed',err);window.__khLastActionError=String(err&&err.message||err||'print failed');});return true;}if(action.actionType==='noop'||action.actionType==='none'){return true;}return false;};if(action.delayMs>0){setTimeout(runner,action.delayMs);return true;}return runner();};");
        script.append("kh.execTriggeredActions=function(triggerType,scanValue){var grouped=window.__khPageActions||{};var actions=Array.isArray(grouped[triggerType])?grouped[triggerType]:[];if(!actions.length)return false;actions.slice().sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);}).forEach(function(action){kh.execAction(action,scanValue||'');});return true;};");
        script.append("kh.attachButtonActions=function(){if(window.__khButtonActionsBound)return;document.addEventListener('click',function(event){var actions=(window.__khPageActions&&window.__khPageActions.button)||[];for(var i=0;i<actions.length;i++){var action=actions[i];if(!action.triggerSelector)continue;var target=event.target&&event.target.closest?event.target.closest(action.triggerSelector):null;if(!target)continue;event.preventDefault();event.stopPropagation();kh.execAction(action,'');return;}},true);window.__khButtonActionsBound=true;};");
        script.append("kh.loadPageActions=function(){var storages=[window.localStorage,window.sessionStorage].filter(Boolean);var getStored=function(key){for(var i=0;i<storages.length;i++){var value=storages[i].getItem(key);if(value)return value;}return '';};var token=getStored('NOCOBASE_TOKEN')||getStored('NOCOBASE_MAIN_TOKEN');var auth=getStored('NOCOBASE_AUTH')||getStored('NOCOBASE_MAIN_AUTH')||'basic';var role=getStored('NOCOBASE_ROLE')||getStored('NOCOBASE_MAIN_ROLE')||'';if(!token||!window.fetch){window.__khPageActions={scan:[],button:[]};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();return Promise.resolve();}var requestUrl=new URL(kh.pageActionsApi,window.location.origin).toString();return window.fetch(requestUrl,{headers:{'Authorization':'Bearer '+token,'X-Authenticator':auth,'X-Requested-With':'XMLHttpRequest'}}).then(function(res){if(!res.ok)throw new Error('page actions '+res.status);return res.json();}).then(function(payload){var data=(payload&&payload.data!==undefined)?payload.data:payload;var items=Array.isArray(data)?data:(Array.isArray(data&&data.items)?data.items:(Array.isArray(data&&data.rows)?data.rows:[]));var scan=[];var button=[];for(var i=0;i<items.length;i++){var action=kh.normalizeAction(items[i],i+1);if(!action||!action.enabled)continue;if(!kh.roleMatch(action.roleName,role))continue;if(!kh.pageMatch(action.pagePath,window.location.href))continue;if(action.triggerType==='button')button.push(action);else if(action.triggerType==='scan')scan.push(action);}scan.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});button.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});window.__khPageActions={scan:scan,button:button};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();}).catch(function(err){console.warn('kh load page actions failed',err);window.__khPageActions={scan:[],button:[]};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();});};");
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
