(function(){
var kh=window.__khClientRuntime;
if(!kh)return;
var firstLifecycleAdapterInstall=!kh._nocoBaseLifecycleAdapterInstalled;
kh._nocoBaseLifecycleAdapterInstalled=true;
var originalPatchHistory=kh.patchHistory;
var fallbackTimer=kh._nocoBaseFallbackTimer||null;
kh.connectNocoBaseEventRuntime=function(){
  if(kh._nocoBaseEventsConnected)return true;
  var lifecycle=window.__khNocoBaseEventRuntime;
  if(!lifecycle||typeof lifecycle.subscribe!=='function')return false;
  kh._nocoBaseEventsConnected=true;
  if(fallbackTimer){clearTimeout(fallbackTimer);fallbackTimer=null;kh._nocoBaseFallbackTimer=null;}
  lifecycle.subscribe('android-client:route','route-change',function(detail){
    kh._nocoBaseReadyPageKey='';
    if(kh.deactivateActionContext)kh.deactivateActionContext('nocobase route-change');
    if(kh.setPageApplyState)kh.setPageApplyState('loading','nocobase route-change');
    window.dispatchEvent(new CustomEvent('kh:routeChanged',{detail:{type:'nocobase',source:detail&&detail.source||''}}));
    if(kh.schedulePageActionRefresh)kh.schedulePageActionRefresh(false);
  });
  lifecycle.subscribe('android-client:settled','dom-settled',function(detail){
    if(kh.reconcileActionContext)kh.reconcileActionContext('nocobase '+String(detail&&detail.source||'dom-settled'));
  });
  lifecycle.subscribe('android-client:ready','page-ready',function(detail){
    if(kh.markUiReady)kh.markUiReady('nocobase '+String(detail&&detail.source||'page-ready'));
    var pageKey=window.location.pathname+window.location.search+window.location.hash;
    if(kh.pageApplyState!=='ready'||kh._nocoBaseReadyPageKey!==pageKey){
      kh._nocoBaseReadyPageKey=pageKey;
      if(kh.schedulePageActionRefresh)kh.schedulePageActionRefresh(false);
    }
  });
  if(kh.pushLog)kh.pushLog('已接入 NocoBase 客户端事件运行时: version='+String(lifecycle.version||'unknown'),'info');
  return true;
};
kh.patchHistory=function(){
  if(kh._historyPatched)return;
  if(kh.connectNocoBaseEventRuntime()){
    kh._historyPatched=true;
    if(kh.pushLog)kh.pushLog('由 NocoBase 事件运行时负责路由监听，跳过 Android history patch','info');
    return;
  }
  if(!fallbackTimer){
    fallbackTimer=setTimeout(function(){
      fallbackTimer=null;
      kh._nocoBaseFallbackTimer=null;
      if(kh._historyPatched||kh.connectNocoBaseEventRuntime())return;
      originalPatchHistory.call(kh);
      if(kh.pushLog)kh.pushLog('未检测到 NocoBase 事件运行时，启用 Android history 兼容回退','warn');
    },1500);
    kh._nocoBaseFallbackTimer=fallbackTimer;
  }
};
if(firstLifecycleAdapterInstall){
  window.addEventListener('kh:nocobase:installed',function(){
    if(kh.connectNocoBaseEventRuntime()&&kh.schedulePageActionRefresh)kh.schedulePageActionRefresh(false);
  });
}
kh.connectNocoBaseEventRuntime();
})();
