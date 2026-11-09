'use strict';
/** Test-only CDP page guard. Install on a fresh, blank isolated browser context BEFORE navigation.
 * All fixture HTML is unmodified. Never use a customer profile or an existing checkout session.
 * No SDK substitutes: actual Widget appearance remains unverified.
 */
function classifyDisplayUrl(value) {
 try {const u=new URL(value),scheme=u.protocol;
  const kind=['http:','https:'].includes(scheme)?'network':['ws:','wss:'].includes(scheme)?'websocket':['data:','blob:','about:','chrome:','devtools:'].includes(scheme)?'internal':'unknown';
  return {kind,scheme,url:u};
 }catch{return {kind:'unknown',scheme:'invalid',url:null};}
}
// Deliberately conservative: until every child execution path has been certified,
// children remain paused and are closed, never resumed or treated as covered.
async function installChildTargetBlocker(root,{allowedTargetId}={}) {
 const stats={targets:[],errors:[]};let violation=null;
 root.on('Target.attachedToTarget',event=>{
  if(event.targetInfo.targetId===allowedTargetId)return;
  const row={type:event.targetInfo.type,paused:event.waitingForDebugger===true,closed:false};stats.targets.push(row);
  violation=row.paused?'unsupported child target blocked':'child target was not paused';
  Promise.resolve(root.send('Target.closeTarget',{targetId:event.targetInfo.targetId})).then(result=>{
   row.closed=result.success!==false;if(!row.closed){violation='child target close failed';stats.errors.push(violation);}
  }).catch(()=>{violation='child target close failed';stats.errors.push(violation);});
 });
 await root.send('Target.setAutoAttach',{autoAttach:true,waitForDebuggerOnStart:true,flatten:true});
 return {stats,assertSafe(){if(violation)throw Error(violation);return stats;}};
}
async function installDisplayGuard(session,{origin,paths,javascript=false}) {
 const base=new URL(origin);
 if(base.protocol!=='http:'||base.hostname!=='127.0.0.1'||!base.port||base.port==='8080'||base.pathname!=='/'||base.search||base.hash||base.username||base.password)throw Error('Isolated loopback origin required');
 const allowed=new Set(paths);
 const safe=p=>typeof p==='string'&&p.startsWith('/')&&!p.startsWith('//')&&!/[\\%#]/.test(p)&&!p.split('?')[0].split('/').some(x=>x==='.'||x==='..');
 if(!allowed.size||[...allowed].some(p=>!safe(p)))throw Error('Registered routes required');
 const stats={postAttempts:0,externalAttempts:0,blockedAttempts:[],readRequestsReleased:0,postRequestsReleased:0,externalRequestsReleased:0,responses:[],observedPostResponses:0,observedExternalResponses:0,errors:[],internalResponses:0,internalAttempts:0,websocketAttempts:0};
 let violation=null;const requestMethods=new Map();
 session.on('Network.requestWillBeSent',event=>{
  requestMethods.set(event.requestId,event.request.method);
  if(classifyDisplayUrl(event.request.url).kind==='unknown'){violation='unknown URL scheme';stats.errors.push(violation);}
 });
 session.on('Fetch.requestPaused',event=>{
  const r=event.request,classified=classifyDisplayUrl(r.url),u=classified.url;
  if(classified.kind==='unknown'){violation='unknown URL scheme';stats.errors.push(violation);}
  if(classified.kind==='internal')stats.internalAttempts++;
  const external=['network','websocket'].includes(classified.kind)&&u.origin!==base.origin;
  if(r.method==='POST')stats.postAttempts++;
  if(external)stats.externalAttempts++;
  const raw=r.url.startsWith(base.origin)?r.url.slice(base.origin.length):'';
  const permit=classified.kind==='network'&&!external&&['GET','HEAD'].includes(r.method)&&safe(raw)&&allowed.has(raw);
  if(!permit)stats.blockedAttempts.push({method:r.method,url:classified.kind==='internal'||classified.kind==='unknown'?classified.scheme:r.url});
  else stats.readRequestsReleased++;
  Promise.resolve(session.send(permit?'Fetch.continueRequest':'Fetch.failRequest',{requestId:event.requestId,...(permit?{}:{errorReason:'BlockedByClient'})})).catch(e=>{violation='interception failed';stats.errors.push('interception failed');});
 });
 session.on('Network.responseReceived',event=>{
  const r=event.response,classified=classifyDisplayUrl(r.url);
  stats.responses.push({url:['internal','unknown'].includes(classified.kind)?classified.scheme:r.url,status:r.status});
  if(classified.kind==='internal'){stats.internalResponses++;return;}
  if(classified.kind==='unknown'){violation='unknown URL scheme';stats.errors.push(violation);return;}
  if(requestMethods.get(event.requestId)==='POST'){stats.observedPostResponses++;violation='POST response observed';stats.errors.push(violation);}
  if(classified.url.origin!==base.origin){stats.observedExternalResponses++;violation='external response observed';stats.errors.push(violation);}
 });
 session.on('Network.webSocketCreated',()=>{stats.websocketAttempts++;violation='WebSocket coverage unverified';stats.errors.push(violation);});
 session.on('Runtime.exceptionThrown',()=>stats.errors.push('page JavaScript exception'));
 await session.send('Network.enable',{});
 await session.send('Network.setCacheDisabled',{cacheDisabled:true});
 await session.send('Network.setBypassServiceWorker',{bypass:true});
 await session.send('Network.setBlockedURLs',{urls:['ws://*','wss://*']});
 await session.send('Emulation.setScriptExecutionDisabled',{value:!javascript});
 await session.send('Fetch.enable',{patterns:[{urlPattern:'*',requestStage:'Request'}]});
 return {stats,assertSafe(){if(violation)throw Error(violation);return stats;}};
}
module.exports={installDisplayGuard,classifyDisplayUrl,installChildTargetBlocker};
