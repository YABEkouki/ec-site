const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = () => fs.readFileSync('src/main/resources/templates/orders/edit-result.html', 'utf8').match(/<script\b[^>]*>([\s\S]*?)<\/script>/)[1];
function page(store = new Map(), {blocked = false, broken = false, navigationFails = false} = {}) {
    let posts = 0;
    const handlers = {};
    const automatic = {value: 'false'}, button = {disabled: false}, status = {textContent: ''};
    const form = {dataset: {receipt: 'receipt-1'}, querySelector: () => automatic,
        querySelectorAll: () => [button], addEventListener: (name, handler) => {handlers[name] = handler;}};
    const context = {window: {sessionStorage: {getItem: key => {if (broken) throw Error('storage');return store.get(key);}, setItem: (key, value) => store.set(key, value)},
        addEventListener: (name, handler) => {handlers[name] = handler;}},
        document: {getElementById: id => id === 'result-continue' ? (blocked ? null : form) : status},
        HTMLFormElement: {prototype: {submit() {if (navigationFails) throw Error('private_error');posts++;}}}};
    vm.createContext(context);vm.runInContext(script(), context);
    return {posts: () => posts, handlers, automatic, button, status};
}
test('eligible result uses existing form once and only shows pending', () => {
    const p = page();assert.equal(p.posts(), 1);assert.equal(p.automatic.value, 'true');
    assert.match(p.status.textContent, /完了していません/);
    let stopped = false;p.handlers.submit({preventDefault() {stopped = true;}});assert.equal(stopped, true);
});
test('unchanged reload never produces an automatic POST loop; manual retry remains', () => {
    const store = new Map();assert.equal(page(store).posts(), 1);
    const again = page(store);assert.equal(again.posts(), 0);assert.equal(again.button.disabled, false);
    again.handlers.submit({preventDefault() {assert.fail('manual fallback must remain');}});assert.equal(again.automatic.value, 'false');
});
test('separate tabs rely on server fencing and use the same receipt', () => {assert.equal(page().posts(), 1);assert.equal(page().posts(), 1);});
test('ineligible page and unavailable session storage do not submit', () => {assert.equal(page(new Map(), {blocked: true}).posts(), 0);assert.equal(page(new Map(), {broken: true}).posts(), 0);});
test('navigation exception keeps manual fallback and does not claim failure or completion', () => {
    const p = page(new Map(), {navigationFails: true});assert.equal(p.posts(), 0);assert.equal(p.button.disabled, false);assert.equal(p.automatic.value, 'false');assert.doesNotMatch(p.status.textContent, /private_error/);
});
test('back-forward restored document permits manual recovery without automatic resubmission', () => {
    const p = page();p.handlers.pageshow({persisted: true});assert.equal(p.posts(), 1);assert.equal(p.button.disabled, false);assert.equal(p.automatic.value, 'false');
});
