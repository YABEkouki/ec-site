const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function page(store = new Map(), {eligible=true, broken=false, fails=false, missingCsrf=false}={}) {
 const html=fs.readFileSync('src/main/resources/templates/orders/edit-result.html','utf8');
 const script=html.match(/<script id="result-auth-start-script"[^>]*>([\s\S]*?)<\/script>/);
 assert.ok(script,'auth-start script must exist');
 let posts=0;const handlers={};const button={disabled:false}, message={textContent:''};
 const form={dataset:{target:'receipt:operation:payment:authorization'},querySelector:()=>missingCsrf?null:{value:'csrf'},querySelectorAll:()=>[button],addEventListener:(n,h)=>handlers[n]=h};
 const context={window:{sessionStorage:{getItem:k=>{if(broken)throw Error('secret');return store.get(k);},setItem:(k,v)=>store.set(k,v)},addEventListener:(n,h)=>handlers[n]=h},
 document:{hidden:false,getElementById:id=>id==='result-auth-start'?(eligible?form:null):message},
 HTMLFormElement:{prototype:{submit(){if(fails)throw Error('secret');posts++;}}}};
 vm.runInNewContext(script[1],context);return {posts:()=>posts,button,message,handlers};
}
test('allowed target posts once using existing form and never claims issued',()=>{const p=page();assert.equal(p.posts(),1);assert.equal(p.button.disabled,true);assert.doesNotMatch(p.message.textContent,/発行しました|完了しました/);});
test('reload never automatically reissues; manual fallback remains',()=>{const s=new Map();page(s);const p=page(s);assert.equal(p.posts(),0);assert.equal(p.button.disabled,false);});
test('ineligible target never posts',()=>assert.equal(page(new Map(),{eligible:false}).posts(),0));
test('unavailable storage fails closed with manual fallback',()=>{const p=page(new Map(),{broken:true});assert.equal(p.posts(),0);assert.equal(p.button.disabled,false);});
test('submit exception keeps attempted marker and manual fallback without secret disclosure',()=>{const s=new Map();const p=page(s,{fails:true});assert.equal(p.posts(),0);assert.equal(p.button.disabled,false);assert.doesNotMatch(p.message.textContent,/secret/);assert.equal(page(s).posts(),0);});
test('duplicate clicks are blocked while navigating',()=>{const p=page();let blocked=false;p.handlers.submit({preventDefault(){blocked=true;}});assert.equal(blocked,true);assert.equal(p.posts(),1);});
test('tabs share no browser permission; server one-time issuance remains authoritative',()=>{assert.equal(page().posts(),1);assert.equal(page().posts(),1);});
test('bfcache restoration does not automatically resubmit',()=>{const p=page();p.handlers.pageshow({persisted:true});assert.equal(p.posts(),1);assert.equal(p.button.disabled,false);});
test('missing CSRF never automatically posts',()=>assert.equal(page(new Map(),{missingCsrf:true}).posts(),0));
