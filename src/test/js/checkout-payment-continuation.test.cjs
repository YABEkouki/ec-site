const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function page(store = new Map(), options = {}) {
    let posts = 0;const handlers = {}, button = {disabled:false}, message = {textContent:''};
    const form = {dataset:{key:options.key || '10:16:24',auto:options.auto === false?'false':'true'},
        querySelectorAll:()=>[button],addEventListener:(name,fn)=>handlers[name]=fn};
    const context={window:{sessionStorage:{getItem:k=>{if(options.storageError)throw Error('storage');return store.get(k);},setItem:(k,v)=>store.set(k,v)},addEventListener:(name,fn)=>handlers[name]=fn},
        document:{getElementById:id=>id==='checkout-continue'?form:message},
        HTMLFormElement:{prototype:{submit(){if(options.sendError)throw Error('private');posts++;}}}};
    vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/templates/fragments/checkout-payment-continuation.html','utf8').match(/<script\b[^>]*>([\s\S]*?)<\/script>/)[1],context);
    return {posts,button,message,handlers};
}
test('eligible callback submits once and does not claim payment success',()=>{const p=page();assert.equal(p.posts,1);assert.equal(p.button.disabled,true);assert.match(p.message.textContent,/確認/);assert.doesNotMatch(p.message.textContent,/注文が完了/);});
test('reload of the same target never automatically repeats',()=>{const s=new Map();assert.equal(page(s).posts,1);const p=page(s);assert.equal(p.posts,0);assert.equal(p.button.disabled,false);});
test('different targets have independent keys',()=>{const s=new Map();page(s);assert.equal(page(s,{key:'11:17:25'}).posts,1);});
test('ineligible and waiting pages never post',()=>assert.equal(page(new Map(),{auto:false}).posts,0));
test('unavailable storage fails closed with manual fallback',()=>{const p=page(new Map(),{storageError:true});assert.equal(p.posts,0);assert.equal(p.button.disabled,false);});
test('navigation exception preserves fallback and never exposes errors',()=>{const p=page(new Map(),{sendError:true});assert.equal(p.posts,0);assert.equal(p.button.disabled,false);assert.doesNotMatch(p.message.textContent,/private/);});
test('double click is suppressed while submitting',()=>{let stopped=false;page().handlers.submit({preventDefault(){stopped=true;}});assert.equal(stopped,true);});
test('back navigation restores manual button without auto replay',()=>{const p=page();p.handlers.pageshow({persisted:true});assert.equal(p.button.disabled,false);});
test('separate tabs depend on server idempotency and keep identical target',()=>{assert.equal(page().posts,1);assert.equal(page().posts,1);});
