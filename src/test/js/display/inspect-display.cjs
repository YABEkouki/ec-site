'use strict';
// Future browser inspection only. This file never starts or connects to the live EC application.
const {EventEmitter}=require('node:events');const fs=require('node:fs');const path=require('node:path');
const {installDisplayGuard}=require('./network-guard.cjs');
class Cdp extends EventEmitter {
 constructor(socket){super();this.socket=socket;this.seq=0;this.pending=new Map();this.sessions=new Map();socket.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.id){const p=this.pending.get(m.id);if(p){clearTimeout(p.timer);this.pending.delete(m.id);m.error?p.reject(Error('CDP command rejected')):p.resolve(m.result);}}else(this.sessions.get(m.sessionId)||this).emit(m.method,m.params);});socket.addEventListener('close',()=>{for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(Error('CDP closed'));}this.pending.clear();});}
 static async connect(endpoint){const u=new URL(endpoint);if(u.protocol!=='ws:'||u.hostname!=='127.0.0.1'||!u.port||u.port==='8080'||u.username||u.password)throw Error('Temporary local browser endpoint required');const socket=new WebSocket(endpoint);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',()=>reject(Error('CDP unavailable')),{once:true});});return new Cdp(socket);}
 send(method,params={},sessionId){return new Promise((resolve,reject)=>{const id=++this.seq;const timer=setTimeout(()=>{this.pending.delete(id);reject(Error('CDP timeout'));},15000);this.pending.set(id,{resolve,reject,timer});this.socket.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));});}
 scope(id){const s=new EventEmitter();s.send=(m,p)=>this.send(m,p,id);this.sessions.set(id,s);return s;}
}
const uncertified='JavaScript-enabled display inspection is not certified; only disabled mode is available.';
function inspectionArguments(args){
 if(args.length!==4&&args.length!==5)throw Error('Use endpoint origin bundle [disabled] /tmp/output');
 const [endpoint,origin,bundle]=args,mode=args.length===4?'disabled':args[3],output=args.at(-1);
 if(mode!=='disabled')throw Error(uncertified);
 return {endpoint,origin,bundle,mode,output};
}
async function inspect({endpoint,origin,bundle,mode='disabled',output}){
 // Reject before reading fixtures, opening sockets or creating browser targets. No override.
 if(mode!=='disabled')throw Error(uncertified);
 const dir=path.resolve(output);if(!dir.startsWith('/tmp/'))throw Error('Private /tmp output required');
 const pages=JSON.parse(fs.readFileSync(bundle,'utf8'));if(!Array.isArray(pages)||!pages.length||pages.some(p=>!p||!(/^[a-z0-9-]+$/).test(p.name)||!p.headers))throw Error('Generated display manifest required');const paths=pages.map(p=>p.path);
 // Validate before creating any browser target.
 const validation=new EventEmitter();validation.send=async()=>({});await installDisplayGuard(validation,{origin,paths,javascript:false});
 fs.mkdirSync(dir,{recursive:true,mode:0o700});const c=await Cdp.connect(endpoint);let contextId;
 try {
  ({browserContextId:contextId}=await c.send('Target.createBrowserContext',{disposeOnDetach:true}));
  const {targetId}=await c.send('Target.createTarget',{url:'about:blank',browserContextId:contextId});
  const {sessionId}=await c.send('Target.attachToTarget',{targetId,flatten:true});const session=c.scope(sessionId);
  // Page scripts remain disabled. Experimental child-target blocker is not invoked.
  const guard=await installDisplayGuard(session,{origin,paths,javascript:false});
  await session.send('Page.enable');await session.send('Runtime.enable');await session.send('Log.enable');
  const consoleMessages=[];session.on('Log.entryAdded',e=>consoleMessages.push({source:e.entry.source,level:e.entry.level}));
  const views=[];
  for(const page of pages.filter(p=>Object.entries(p.headers).some(([k,v])=>k.toLowerCase()==='content-type'&&v.startsWith('text/html')))){
   for(const width of [1280,375]){
    await session.send('Emulation.setDeviceMetricsOverride',{width,height:812,deviceScaleFactor:1,mobile:width===375});
    await session.send('Page.navigate',{url:origin+page.path});
    await new Promise(resolve=>setTimeout(resolve,350));guard.assertSafe();
    // Runtime.evaluate only reads DOM; it does not enable disabled page scripts.
    const {result}=await session.send('Runtime.evaluate',{expression:'({url:location.href,header:!!document.querySelector(".site-header"),main:!!document.querySelector("main"),viewport:document.querySelector("meta[name=viewport]")?.content,width:innerWidth,scrollWidth:document.documentElement.scrollWidth})',returnByValue:true});
    views.push({name:page.name,width,dom:result.value});
    const shot=await session.send('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(dir,page.name+'-'+width+'.png'),Buffer.from(shot.data,'base64'),{mode:0o600});
   }
  }
  fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify({mode,views,network:guard.assertSafe(),console:consoleMessages,actualServerPosts:'Check display-server received POST counter; requestWillBeSent is not proof of transmission',widget:'UNVERIFIED: SDK blocked'},null,2),{mode:0o600});
 } finally {if(contextId)await c.send('Target.disposeBrowserContext',{browserContextId:contextId});c.socket.close();}
}
if(require.main===module){
 Promise.resolve().then(()=>inspect(inspectionArguments(process.argv.slice(2)))).catch(error=>{
  console.error(error.message===uncertified?uncertified:'Display inspection failed safely; inspect only the dedicated fixture environment.');process.exitCode=1;
 });
}
module.exports={inspect,Cdp,inspectionArguments};
