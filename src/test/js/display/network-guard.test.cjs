const {test}=require('node:test');const assert=require('node:assert/strict');const {EventEmitter}=require('node:events');
const {installDisplayGuard}=require('./network-guard.cjs');
class Session extends EventEmitter{calls=[];async send(method,params){this.calls.push({method,params});return {};}}
async function paused(s,url,method='GET'){s.emit('Fetch.requestPaused',{requestId:String(s.calls.length),request:{url,method}});await new Promise(r=>setImmediate(r));}
test('fails closed to external, POST, unregistered paths and traversal',async()=>{const s=new Session();const g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/orders/900001/edit','/css/style.css'],javascript:true});
 await paused(s,'http://127.0.0.1:49123/css/style.css');await paused(s,'http://127.0.0.1:49123/orders/900001/edit','POST');await paused(s,'https://js.pay.jp/payments.js');await paused(s,'http://127.0.0.1:8080/login');await paused(s,'http://127.0.0.1:49123/unknown');await paused(s,'http://127.0.0.1:49123/%2e%2e/css/style.css');
 assert.equal(s.calls.filter(c=>c.method==='Fetch.continueRequest').length,1);assert.equal(s.calls.filter(c=>c.method==='Fetch.failRequest').length,5);assert.equal(g.stats.postAttempts,1);assert.equal(g.stats.externalAttempts,2);assert.equal(g.stats.externalRequestsReleased,0);assert.equal(g.stats.postRequestsReleased,0);
});
test('explicit JavaScript-disabled mode and all-request interception precede navigation',async()=>{const s=new Session();await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/'],javascript:false});assert(s.calls.some(c=>c.method==='Emulation.setScriptExecutionDisabled'&&c.params.value));assert(s.calls.some(c=>c.method==='Fetch.enable'&&c.params.patterns[0].urlPattern==='*'));});
test('rejects production origin and requires registered pages',async()=>{await assert.rejects(installDisplayGuard(new Session(),{origin:'http://127.0.0.1:8080',paths:['/']}));await assert.rejects(installDisplayGuard(new Session(),{origin:'https://api.pay.jp',paths:['/']}));});
test('tracks observed responses separately and fails if an external response bypasses the guard',async()=>{const s=new Session();const g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/']});s.emit('Network.responseReceived',{response:{url:'https://api.pay.jp/forbidden',status:200}});assert.equal(g.stats.responses.length,1);assert.throws(()=>g.assertSafe(),/external response/);});
test('interception transport failure is not reported as safe',async()=>{const s=new Session();const g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/']});s.send=async()=>{throw Error('private transport detail')};await paused(s,'https://js.pay.jp/payments.js');assert.throws(()=>g.assertSafe(),/interception failed/);assert(!JSON.stringify(g.stats).includes('private transport detail'));});
test('inspection driver refuses live app origin before connecting to any browser',async()=>{const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const {inspect}=require('./inspect-display.cjs');const dir=fs.mkdtempSync(path.join(os.tmpdir(),'display-guard-test-'));const bundle=path.join(dir,'pages.json');fs.writeFileSync(bundle,JSON.stringify([{name:'fixture',path:'/',headers:{'Content-Type':'text/html'}}]));try{await assert.rejects(inspect({endpoint:'ws://127.0.0.1:1',origin:'http://127.0.0.1:8080',bundle,mode:'disabled',output:dir}),/Isolated loopback origin required/);}finally{fs.rmSync(dir,{recursive:true,force:true});}});

test('counts a POST response as a hard failure rather than treating request observation as transmission proof',async()=>{const s=new Session();const g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/']});s.emit('Network.requestWillBeSent',{requestId:'bypass',request:{method:'POST',url:'http://127.0.0.1:49123/'}});assert.equal(g.stats.observedPostResponses,0);s.emit('Network.responseReceived',{requestId:'bypass',response:{url:'http://127.0.0.1:49123/',status:405}});assert.equal(g.stats.observedPostResponses,1);assert.throws(()=>g.assertSafe(),/POST response observed/);});

const {classifyDisplayUrl,installChildTargetBlocker}=require('./network-guard.cjs');
test('classifies network, sockets, internal URLs and unknown schemes consistently',()=>{
 for(const scheme of ['http','https'])assert.equal(classifyDisplayUrl(scheme+'://example.invalid/').kind,'network');
 for(const scheme of ['ws','wss'])assert.equal(classifyDisplayUrl(scheme+'://example.invalid/').kind,'websocket');
 for(const url of ['data:text/plain,hello','blob:http://127.0.0.1:49123/id','about:blank','chrome://error/','devtools://devtools/'])assert.equal(classifyDisplayUrl(url).kind,'internal');
 assert.equal(classifyDisplayUrl('custom:secret').kind,'unknown');assert.equal(classifyDisplayUrl('invalid').kind,'unknown');
});
test('internal responses are not network responses; unknown schemes fail closed in both event paths',async()=>{
 const s=new Session(),g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/']});
 for(const url of ['data:image/png;base64,AAAA','blob:http://127.0.0.1:49123/id','about:blank','chrome://error/','devtools://devtools/']){
  await paused(s,url);s.emit('Network.responseReceived',{response:{url,status:200}});
 }
 assert.equal(g.stats.externalAttempts,0);assert.equal(g.stats.observedExternalResponses,0);assert.equal(g.stats.internalResponses,5);g.assertSafe();
 assert(!JSON.stringify(g.stats).includes('AAAA'));
 await paused(s,'unknown:private');assert.throws(()=>g.assertSafe(),/unknown URL scheme/);
 const s2=new Session(),g2=await installDisplayGuard(s2,{origin:'http://127.0.0.1:49123',paths:['/']});
 s2.emit('Network.responseReceived',{response:{url:'unknown:private',status:200}});assert.throws(()=>g2.assertSafe(),/unknown URL scheme/);
});
test('child target blocker requests paused browser auto-attach and never resumes children',async()=>{
 const root=new Session();const b=await installChildTargetBlocker(root);
 assert.deepEqual(root.calls[0],{method:'Target.setAutoAttach',params:{autoAttach:true,waitForDebuggerOnStart:true,flatten:true}});
 for(const type of ['page','iframe','worker','shared_worker','service_worker'])root.emit('Target.attachedToTarget',{sessionId:type,targetInfo:{type,targetId:type},waitingForDebugger:true});
 await new Promise(r=>setImmediate(r));assert.equal(root.calls.filter(c=>c.method==='Target.closeTarget').length,5);
 assert.equal(root.calls.filter(c=>c.method==='Runtime.runIfWaitingForDebugger').length,0);
 assert.throws(()=>b.assertSafe(),/child target/);
});
test('unpaused or unrecognized targets and failed close are hard failures',async()=>{
 for(const event of [{targetInfo:{type:'page',targetId:'a'},waitingForDebugger:false},{targetInfo:{type:'other',targetId:'b'},waitingForDebugger:true}]){
  const root=new Session(),b=await installChildTargetBlocker(root);root.emit('Target.attachedToTarget',event);await new Promise(r=>setImmediate(r));assert.throws(()=>b.assertSafe());
 }
 const root=new Session(),b=await installChildTargetBlocker(root);root.send=async()=>{throw Error('secret')};root.emit('Target.attachedToTarget',{targetInfo:{type:'worker',targetId:'w'},waitingForDebugger:true});await new Promise(r=>setImmediate(r));assert.throws(()=>b.assertSafe());assert(!JSON.stringify(b.stats).includes('secret'));
});

test('request observation also rejects an unknown scheme even without a Fetch event',async()=>{const s=new Session(),g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/']});s.emit('Network.requestWillBeSent',{requestId:'unknown',request:{url:'custom:private',method:'GET'}});assert.throws(()=>g.assertSafe(),/unknown URL scheme/);});
test('WebSocket creation fails certification even when URL blocking is configured',async()=>{const s=new Session(),g=await installDisplayGuard(s,{origin:'http://127.0.0.1:49123',paths:['/']});s.emit('Network.webSocketCreated',{url:'ws://127.0.0.1:49124/'});assert.equal(g.stats.websocketAttempts,1);assert.throws(()=>g.assertSafe(),/WebSocket coverage unverified/);});

test('JavaScript-enabled inspection modes are rejected before filesystem or CDP access',async()=>{
 const {inspect}=require('./inspect-display.cjs');
 for(const mode of ['blocked','enabled','javascript',true])await assert.rejects(inspect({mode}),/JavaScript-enabled display inspection is not certified/);
});
test('CLI defaults to disabled; explicit enabled mode has no override',()=>{
 const {inspectionArguments}=require('./inspect-display.cjs');
 assert.deepEqual(inspectionArguments(['endpoint','origin','bundle','/tmp/out']),{endpoint:'endpoint',origin:'origin',bundle:'bundle',mode:'disabled',output:'/tmp/out'});
 assert.equal(inspectionArguments(['e','o','b','disabled','/tmp/out']).mode,'disabled');
 for(const mode of ['blocked','enabled','--allow-unsafe'])assert.throws(()=>inspectionArguments(['e','o','b',mode,'/tmp/out']),/not certified/);
});
test('CLI prints the uncertified-mode refusal without starting a browser',()=>{
 const {spawnSync}=require('node:child_process'),path=require('node:path');
 const env={...process.env};delete env.NODE_TEST_CONTEXT;
 const result=spawnSync(process.execPath,[path.join(__dirname,'inspect-display.cjs'),'ws://127.0.0.1:1','http://127.0.0.1:49123','/nonexistent-manifest','blocked','/tmp/unused-display-output'],{encoding:'utf8',timeout:5000,env});
 assert.equal(result.error,undefined);assert.equal(result.status,1);assert.match(result.stderr,/JavaScript-enabled display inspection is not certified/);
});
