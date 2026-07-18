(function(){
var kh=window.__khClientRuntime;
if(!kh)return;
var firstActionEventInstall=!kh._actionEventRuntimeInstalled;
kh._actionEventRuntimeInstalled=true;
kh.actionEventProtocolVersion='1';
kh._actionContextGeneration=Number(kh._actionContextGeneration||0);
kh._pendingActionEvents=Array.isArray(kh._pendingActionEvents)?kh._pendingActionEvents:[];
kh._pendingActionWaits=kh._pendingActionWaits||{};

kh.currentPageKey=function(){
  return window.location.pathname+window.location.search+window.location.hash;
};

kh.emitActionRuntimeEvent=function(name,detail){
  var payload=detail&&typeof detail==='object'?detail:{};
  payload.eventName=String(name||'');
  payload.pageKey=String(payload.pageKey||kh.currentPageKey());
  payload.generation=Number(payload.generation||kh._actionContextGeneration||0);
  payload.timestamp=Date.now();
  window.dispatchEvent(new CustomEvent('kh:action-runtime:'+String(name||'event'),{detail:payload}));
  return payload;
};

kh.installPhotoActions=function(actions){
  Array.from(document.querySelectorAll('[data-kh-photo-action]')).forEach(function(button){button.remove();});
};
kh.installPhotoActions.__khUsesNativeUploadChooser=true;

kh.deactivateActionContext=function(reason){
  var previous=kh.actionContext||null;
  kh.cancelAllPendingActionWaits&&kh.cancelAllPendingActionWaits('context-deactivated');
  kh._actionContextGeneration+=1;
  kh.actionContext={
    active:false,
    generation:kh._actionContextGeneration,
    pageKey:kh.currentPageKey(),
    reason:String(reason||'deactivated'),
    groups:{scan:[],button:[],attachment:[]}
  };
  window.__khPageActions=kh.actionContext.groups;
  var bridge=kh.getNativeBridge&&kh.getNativeBridge();
  if(bridge&&bridge.setScanActionEnabled)bridge.setScanActionEnabled(false);
  kh.emitActionRuntimeEvent('context-deactivated',{
    pageKey:kh.actionContext.pageKey,
    generation:kh.actionContext.generation,
    reason:kh.actionContext.reason,
    previousPageKey:previous&&previous.pageKey||''
  });
  return kh.actionContext;
};

kh.reconcileActionContext=function(source){
  var context=kh.actionContext;
  if(!context||!context.active||context.pageKey!==kh.currentPageKey())return false;
  kh.installPhotoActions(context.groups.attachment||[]);
  kh.emitActionRuntimeEvent('host-settled',{
    pageKey:context.pageKey,
    generation:context.generation,
    source:String(source||'dom-settled')
  });
  return true;
};

kh.actionConfigIdentity=function(action){
  var options=action&&action.options||{};
  return [
    String(action&&action.triggerType||''),
    String(action&&action.actionType||''),
    String(action&&action.triggerSelector||''),
    String(action&&action.targetSelector||''),
    String(options.attachment_field_name||options.attachmentFieldName||''),
    String(action&&action.value||''),
    JSON.stringify(options)
  ].join('@@');
};

kh.dedupeContextActions=function(actions){
  var selected={};
  (actions||[]).forEach(function(action,index){
    var key=kh.actionConfigIdentity(action);
    var pagePath=String(action&&action.pagePath||'');
    var score=pagePath.split('/').filter(Boolean).length*10000+pagePath.length;
    var current=selected[key];
    if(!current||score>current.score||(score===current.score&&(action.sortOrder||index)<(current.action.sortOrder||current.index))){
      selected[key]={action:action,score:score,index:index};
    }
  });
  return Object.keys(selected).map(function(key){return selected[key].action;});
};

kh.activateActionContext=function(groups,signature,role){
  var pageKey=kh.currentPageKey();
  kh.cancelAllPendingActionWaits&&kh.cancelAllPendingActionWaits('context-reactivated');
  kh._actionContextGeneration+=1;
  kh.actionContext={
    active:true,
    generation:kh._actionContextGeneration,
    pageKey:pageKey,
    signature:String(signature||''),
    role:String(role||''),
    groups:groups
  };
  window.__khPageActions=groups;
  window.__khExecTriggeredActions=kh.execTriggeredActions;
  kh.attachButtonActions();
  kh.installPhotoActions(groups.attachment||[]);
  var bridge=kh.getNativeBridge();
  if(bridge&&bridge.setScanActionEnabled)bridge.setScanActionEnabled((groups.scan||[]).length>0);
  kh.pageApplySignature=String(signature||'');
  kh.getActionCatalogStore().lastAppliedKey=kh.pageApplySignature;
  kh.setPageApplyState('ready','event-context generation='+kh.actionContext.generation+',scan='+(groups.scan||[]).length+',button='+(groups.button||[]).length+',attachment='+(groups.attachment||[]).length);
  kh.emitActionRuntimeEvent('context-activated',{
    pageKey:pageKey,
    generation:kh.actionContext.generation,
    scanCount:(groups.scan||[]).length,
    buttonCount:(groups.button||[]).length,
    attachmentCount:(groups.attachment||[]).length
  });
  kh.flushPendingActionEvents();
  return groups;
};

kh.applyPageActionsFromCatalog=function(items,role){
  var scan=[];
  var button=[];
  var attachment=[];
  for(var i=0;i<items.length;i++){
    var action=kh.normalizeAction(items[i],i+1);
    if(!action||!action.enabled)continue;
    if(!kh.actionPlatformMatch(action))continue;
    if(!kh.pageMatch(action.pagePath,window.location.href))continue;
    if(action.triggerType==='button')button.push(action);
    else if(action.triggerType==='scan')scan.push(action);
    else if(action.triggerType==='attachment'&&action.actionType==='capture_photo_upload')attachment.push(action);
  }
  scan=kh.dedupeContextActions(scan);
  button=kh.dedupeContextActions(button);
  attachment=kh.dedupeContextActions(attachment);
  scan.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});
  button.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});
  attachment.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});
  var groups={scan:scan,button:button,attachment:attachment};
  var tokenPresent=kh.getActionAuth().token?'1':'0';
  var signature=[kh.currentPageKey(),tokenPresent,String(kh.actionCatalogVersion||0),kh.getActionSignature(scan,button,attachment)].join('@@');
  var current=kh.actionContext;
  if(current&&current.active&&current.pageKey===kh.currentPageKey()&&current.signature===signature){
    kh.reconcileActionContext('catalog-reused');
    kh.reportPageReadyState('ready','event-context reused');
    kh.flushPendingActionEvents();
    return Promise.resolve(current.groups);
  }
  kh.pushLog('页面动作上下文已激活: scan='+scan.length+', button='+button.length+', attachment='+attachment.length+', page='+kh.currentPageKey(),'info');
  return Promise.resolve(kh.activateActionContext(groups,signature,role));
};

kh.isActionTargetInteractable=function(action){
  if(!action)return false;
  if(['fill_input','fill','scan_fill','input'].indexOf(action.actionType)>=0){
    if(!action.targetSelector)return false;
    return kh.checkSelector(action.targetSelector,true,true).interactableCount>0;
  }
  if(['click','tap'].indexOf(action.actionType)>=0){
    if(!action.targetSelector)return false;
    return kh.checkSelector(action.targetSelector,true,false).interactableCount>0;
  }
  return true;
};

kh.cancelAllPendingActionWaits=function(reason){
  var waits=kh._pendingActionWaits||{};
  Object.keys(waits).forEach(function(key){
    try{waits[key](String(reason||'cancelled'));}catch(e){}
  });
  kh._pendingActionWaits={};
};

kh.executeDeferredClickEvent=function(action,value,source,eventContext){
  var delay=Math.max(0,Number(action.delayMs||0));
  var options=action.options||{};
  var waitMs=Math.max(0,Number(options.target_wait_ms||options.targetWaitMs||1500));
  var waitKey=String(eventContext.generation)+'::'+String(action.id||action.actionType||'click');
  var previous=kh._pendingActionWaits[waitKey];
  if(previous)previous('superseded-by-new-scan');
  var settled=false;
  var observer=null;
  var waitTimer=null;
  var delayTimer=null;
  var cleanup=function(){
    if(observer)observer.disconnect();
    if(waitTimer)clearTimeout(waitTimer);
    if(delayTimer)clearTimeout(delayTimer);
    if(kh._pendingActionWaits[waitKey]===cancel)delete kh._pendingActionWaits[waitKey];
  };
  var finish=function(consumed,reason){
    if(settled)return;
    settled=true;
    cleanup();
    if(consumed){
      var immediate=Object.assign({},action,{delayMs:0});
      kh.execAction(immediate,value,source);
      kh.emitActionRuntimeEvent('action-consumed',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,actionId:action.id});
    }else{
      kh.emitActionRuntimeEvent('action-dormant',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,actionId:action.id,reason:String(reason||'cancelled')});
    }
  };
  var cancel=function(reason){finish(false,reason||'cancelled');};
  kh._pendingActionWaits[waitKey]=cancel;
  var run=function(){
    delayTimer=null;
    if(!kh.actionContext||!kh.actionContext.active||kh.actionContext.generation!==eventContext.generation||kh.currentPageKey()!==eventContext.pageKey){finish(false,'context-changed');return;}
    if(kh.isActionTargetInteractable(action)){finish(true,'target-ready');return;}
    if(!window.MutationObserver||!document.documentElement||waitMs<=0){finish(false,'target-not-interactable');return;}
    var check=function(){
      if(!kh.actionContext||kh.actionContext.generation!==eventContext.generation||kh.currentPageKey()!==eventContext.pageKey){finish(false,'context-changed');return;}
      if(kh.isActionTargetInteractable(action))finish(true,'target-ready');
    };
    observer=new MutationObserver(function(){check();});
    observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','disabled','aria-hidden']});
    waitTimer=setTimeout(function(){finish(false,'target-timeout');},waitMs);
    check();
  };
  if(delay>0)delayTimer=setTimeout(run,delay);else run();
  return true;
};

kh.consumeScanActionEvent=function(eventContext){
  var context=kh.actionContext;
  if(!context||!context.active||context.pageKey!==eventContext.pageKey||context.generation!==eventContext.generation)return false;
  var actions=(context.groups.scan||[]).slice().sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});
  var consumed=false;
  var fillReadiness={};
  var hasActiveFill=false;
  actions.forEach(function(action){
    if(['fill_input','fill','scan_fill','input'].indexOf(action.actionType)<0)return;
    var ready=kh.isActionTargetInteractable(action);
    fillReadiness[String(action.id||action.targetSelector||'')]=ready;
    if(ready)hasActiveFill=true;
  });
  actions.forEach(function(action){
    if(['fill_input','fill','scan_fill','input'].indexOf(action.actionType)>=0){
      if(!fillReadiness[String(action.id||action.targetSelector||'')]){
        kh.emitActionRuntimeEvent('action-dormant',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,actionId:action.id,reason:'hidden-or-unmounted-input'});
        return;
      }
      kh.execAction(action,eventContext.value,eventContext.source);
      kh.emitActionRuntimeEvent('action-consumed',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,actionId:action.id});
      consumed=true;
      return;
    }
    if(['click','tap'].indexOf(action.actionType)>=0){
      var options=action.options||{};
      var targetReady=kh.isActionTargetInteractable(action);
      var waitWithoutFill=options.wait_without_fill===true||options.waitWithoutFill===true;
      if(!targetReady&&!hasActiveFill&&!waitWithoutFill){
        kh.emitActionRuntimeEvent('action-dormant',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,actionId:action.id,reason:'no-active-fill-for-deferred-click'});
        return;
      }
      kh.executeDeferredClickEvent(action,eventContext.value,eventContext.source,eventContext);
      consumed=true;
      return;
    }
    kh.execAction(action,eventContext.value,eventContext.source);
    kh.emitActionRuntimeEvent('action-consumed',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,actionId:action.id});
    consumed=true;
  });
  if(!consumed)kh.emitActionRuntimeEvent('scan-unhandled',{pageKey:eventContext.pageKey,generation:eventContext.generation,scanEventId:eventContext.id,reason:'no-active-consumer'});
  return consumed;
};

kh.dispatchScanActionEvent=function(value,source){
  var text=String(value||'').trim();
  if(!text)return false;
  var pageKey=kh.currentPageKey();
  var context=kh.actionContext;
  var eventContext={
    id:'scan-'+Date.now()+'-'+Math.random().toString(36).slice(2,8),
    value:text,
    source:String(source||'scan'),
    pageKey:pageKey,
    generation:context&&context.active&&context.pageKey===pageKey?context.generation:0,
    timestamp:Date.now()
  };
  if(!context||!context.active||context.pageKey!==pageKey){
    kh._pendingActionEvents.push(eventContext);
    if(kh._pendingActionEvents.length>10)kh._pendingActionEvents=kh._pendingActionEvents.slice(-10);
    kh.emitActionRuntimeEvent('scan-queued',eventContext);
    return true;
  }
  kh.emitActionRuntimeEvent('scan',eventContext);
  return true;
};

kh.flushPendingActionEvents=function(){
  var context=kh.actionContext;
  if(!context||!context.active)return false;
  var pending=kh._pendingActionEvents.splice(0);
  pending.forEach(function(item){
    if(item.pageKey!==context.pageKey){
      kh.emitActionRuntimeEvent('scan-dropped',{id:item.id,pageKey:item.pageKey,generation:context.generation,reason:'route-changed'});
      return;
    }
    item.generation=context.generation;
    kh.emitActionRuntimeEvent('scan',item);
  });
  return pending.length>0;
};

kh.flushPendingScanQueue=function(){
  var legacy=Array.isArray(kh._pendingScanQueue)?kh._pendingScanQueue.splice(0):[];
  legacy.forEach(function(item){kh.dispatchScanActionEvent(item&&item.value||'','legacy-queue');});
  return Promise.resolve(legacy.length>0);
};

kh.ensureScanBridge=function(){
  if(kh._scanBridgeReady)return kh._scanBridgeReady;
  var plugin=kh.getScanPlugin();
  if(!plugin||!plugin.addListener){
    kh._scanBridgeReady=Promise.reject(new Error('Scan bridge unavailable'));
    return kh._scanBridgeReady;
  }
  kh._scanBridgeReady=Promise.resolve(plugin.addListener('scanResult',function(evt){
    var value=evt&&evt.value?String(evt.value):'';
    if(!value)return;
    var bridge=kh.getNativeBridge();
    if(bridge&&bridge.onScanCompleted)bridge.onScanCompleted();
    kh.pushLog('收到扫码事件: page='+kh.currentPageKey()+', state='+kh.pageApplyState,'ok');
    kh.dispatchScanActionEvent(value,'scanResult');
  })).then(function(){
    kh.pushLog('扫码事件桥已就绪','info');
    return true;
  }).catch(function(err){
    kh.pushLog('扫码事件桥初始化失败: '+String(err&&err.message||err||'unknown'),'err');
    throw err;
  });
  return kh._scanBridgeReady;
};

kh.execTriggeredActions=function(triggerType,value,source){
  if(triggerType==='scan')return kh.dispatchScanActionEvent(value,source||'legacy-dispatch');
  var context=kh.actionContext;
  var groups=context&&context.active?context.groups:(window.__khPageActions||{});
  var actions=Array.isArray(groups[triggerType])?groups[triggerType]:[];
  if(!actions.length)return false;
  actions.slice().sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);}).forEach(function(action){kh.execAction(action,value||'',source||triggerType);});
  return true;
};

kh.notifyWebReady=function(detail,source,forceRefresh){
  var pageKey=kh.currentPageKey();
  kh._webReadyNotified=true;
  kh._uiReadyObserved=true;
  if(kh._readyFallbackTimer){clearTimeout(kh._readyFallbackTimer);kh._readyFallbackTimer=null;}
  if(kh._uiReadyObserver&&kh._uiReadyObserver.disconnect){try{kh._uiReadyObserver.disconnect();}catch(e){}}
  kh._uiReadyObserver=null;
  kh.emitActionRuntimeEvent('page-ready',{pageKey:pageKey,source:String(source||'web'),detail:String(detail||'ready')});
  if(kh.actionContext&&kh.actionContext.active&&kh.actionContext.pageKey===pageKey){
    kh.reconcileActionContext(String(source||'web'));
    kh.reportPageReadyState('ready','event page-ready reused');
    return true;
  }
  if(!kh._uiReadyRefreshQueued){
    kh._uiReadyRefreshQueued=true;
    setTimeout(function(){
      kh._uiReadyRefreshQueued=false;
      kh.refreshCurrentPage(forceRefresh!==false).catch(function(){return null;});
    },0);
  }
  return true;
};

kh.markUiReady=function(detail){
  return kh.notifyWebReady(detail||'ui-ready','observer',false);
};

kh._eventDiagnoseActionBase=kh.diagnoseAction;
kh.diagnoseAction=function(action,context){
  var diagnosis=kh._eventDiagnoseActionBase(action,context);
  if(!diagnosis||!diagnosis.enabled||!diagnosis.participatesInCurrentPage)return diagnosis;
  var supported=['fill_input','fill','scan_fill','input','click','tap','print_label','print_batch_label','scan','start_scan','device_scan','capture_photo_upload','noop','none'];
  if(supported.indexOf(diagnosis.actionType)<0)return diagnosis;
  var hardErrors={ACTION_DISABLED:true,PAGE_MISMATCH:true,TARGET_SELECTOR_MISSING:true,TRIGGER_SELECTOR_MISSING:true,OPTIONS_INVALID:true};
  var reasons=diagnosis.reasons||[];
  if(reasons.some(function(reason){return reason&&hardErrors[reason.code];}))return diagnosis;
  var deferredCodes={TRIGGER_SELECTOR_NO_MATCH:true,TARGET_SELECTOR_NO_MATCH:true,TARGET_NOT_INTERACTABLE:true,TARGET_WAITING_VISIBILITY:true,FIELD_SELECTOR_NO_MATCH:true,FIELD_VALUE_EMPTY:true,ACTION_TYPE_UNSUPPORTED:true};
  var deferred=reasons.filter(function(reason){return reason&&deferredCodes[reason.code];});
  diagnosis.reasons=reasons.filter(function(reason){return !reason||!deferredCodes[reason.code];});
  diagnosis.warnings=diagnosis.warnings||[];
  if(deferred.length||diagnosis.status==='waiting'||diagnosis.status==='unavailable'){
    diagnosis.warnings.push({code:'EVENT_TIME_TARGET_CHECK',message:'动作配置已激活；控件目标在事件执行时检查，不影响页面 ready'});
  }
  diagnosis.status='available';
  return diagnosis;
};

if(firstActionEventInstall){
  window.addEventListener('kh:action-runtime:scan',function(event){
    kh.consumeScanActionEvent(event&&event.detail||{});
  });
  window.addEventListener('kh:routeChanged',function(){
    var context=kh.actionContext;
    if(context&&context.pageKey!==kh.currentPageKey()){
      kh.deactivateActionContext('route-changed');
      kh.setPageApplyState('loading','route context changed');
    }
  });
}

kh.pushLog('动作事件运行时已安装: protocol='+kh.actionEventProtocolVersion,'info');
})();
