(()=>{var khRuntimeValues=window.__khRuntimeValues||{};
if(kh.isFeatureEnabled('enableNetworkHeaderPatch',true)){patchFetch();patchXhr();}else{kh.pushLog('网络请求头 patch 已关闭','warn');}
if(khRuntimeValues.shouldBootstrap){
try{
var storages=[window.localStorage,window.sessionStorage].filter(Boolean);
var setValue=function(storage,key,val){if(val===null||val===undefined||val===''){storage.removeItem(key);}else{storage.setItem(key,val);}};
var token=String(khRuntimeValues.khToken||'');
var auth=String(khRuntimeValues.khAuth||'basic');
var role=String(khRuntimeValues.khRole||'');
var app=String(khRuntimeValues.khApp||'');
var paper=String(khRuntimeValues.khPaper||'');
var redirect=String(khRuntimeValues.redirect||'');
var prefixes=[String(khRuntimeValues.nocobaseStoragePrefix||'NOCOBASE_')];
if(app){prefixes.push(String(khRuntimeValues.nocobaseStoragePrefix||'NOCOBASE_') + app.toUpperCase() + '_');}
kh.pushLog('注入登录态并跳转到业务页: '+redirect,'info');
prefixes.forEach(function(prefix){storages.forEach(function(storage){setValue(storage,prefix+'TOKEN',token);setValue(storage,prefix+'AUTH',auth);setValue(storage,prefix+'ROLE',role);});});
storages.forEach(function(storage){setValue(storage,kh.paperTypeStorageKey,paper);});
window.location.replace(redirect);
return;
}catch(e){console.error('kh bootstrap failed',e);}
}
kh.bootOnce().then(function(){return kh.refreshCurrentPage(false);}).catch(function(){return null;});if(!kh._bootstrapLifecycleListenersInstalled){kh._bootstrapLifecycleListenersInstalled=true;window.addEventListener('DOMContentLoaded',function(){kh.schedulePageActionRefresh(false);},{once:true});window.addEventListener('pageshow',function(){kh.schedulePageActionRefresh(false);});window.addEventListener('hashchange',function(){window.dispatchEvent(new CustomEvent('kh:routeChanged',{detail:{type:'hashchange'}}));kh.schedulePageActionRefresh(false);});window.addEventListener('popstate',function(){window.dispatchEvent(new CustomEvent('kh:routeChanged',{detail:{type:'popstate'}}));kh.schedulePageActionRefresh(false);});}
})();
