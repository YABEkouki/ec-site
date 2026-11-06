const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = () => fs.readFileSync('src/main/resources/templates/orders/edit-result.html', 'utf8').match(/<script\b[^>]*>([\s\S]*?)<\/script>/)[1];
function page(store = new Map(), {blocked = false, broken = false, navigationFails = false, delay = 0, remaining = 30000, stage = 'INITIAL:0', initialClock = 0} = {}) {
    let posts = 0, now = initialClock, nextTimer = 1;
    const timers = new Map();
    const handlers = {};
    const automatic = {value: 'false'}, button = {disabled: false}, status = {textContent: ''};
    const form = {dataset: {receipt: 'receipt-1', stage, waitMs: String(delay), remainingMs: String(remaining)}, querySelector: () => automatic,
        querySelectorAll: () => [button], addEventListener: (name, handler) => {handlers[name] = handler;}};
    const context = {window: {sessionStorage: {getItem: key => {if (broken) throw Error('storage');return store.get(key);}, setItem: (key, value) => store.set(key, value)},
        addEventListener: (name, handler) => {handlers[name] = handler;}},
        document: {hidden: false, addEventListener: (name, handler) => {handlers[name] = handler;}, getElementById: id => id === 'result-continue' ? (blocked ? null : form) : status},
        Date: {now: () => now}, setTimeout: (fn, ms) => {const id = nextTimer++;timers.set(id, {fn, at: now + ms});return id;}, clearTimeout: id => timers.delete(id),
        HTMLFormElement: {prototype: {submit() {if (navigationFails) throw Error('private_error');posts++;}}}};
    vm.createContext(context);vm.runInContext(script(), context);
    return {posts: () => posts, handlers, automatic, button, status, context, advance(ms) {now += ms;for (const [id, timer] of [...timers]) if (timer.at <= now) {timers.delete(id);timer.fn();}}};
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

test('reauthorization requeue waits for server backoff then posts once', () => {
    const p = page(new Map(), {stage: 'REAUTH:3:4', delay: 5000});
    assert.equal(p.posts(), 0);p.advance(4999);assert.equal(p.posts(), 0);p.advance(1);assert.equal(p.posts(), 1);p.advance(10000);assert.equal(p.posts(), 1);
});
test('expired/throttled wait never posts and page leave cancels timer', () => {
    const expired = page(new Map(), {stage: 'REAUTH:3:4', delay: 5000, remaining: 4000});expired.advance(5000);assert.equal(expired.posts(), 0);
    const late = page(new Map(), {stage: 'REAUTH:3:4', delay: 5000});late.advance(11000);assert.equal(late.posts(), 0);
    const left = page(new Map(), {stage: 'REAUTH:3:4', delay: 5000});left.handlers.pagehide();left.advance(5000);assert.equal(left.posts(), 0);
    const hidden = page(new Map(), {stage: 'REAUTH:3:4', delay: 5000});hidden.context.document.hidden = true;hidden.handlers.visibilitychange();hidden.advance(5000);assert.equal(hidden.posts(), 0);
});
test('stages/versions have separate one-shot keys but share a finite receipt budget', () => {
    const store = new Map();assert.equal(page(store).posts(), 1);
    const reauth = page(store, {stage: 'REAUTH:3:4', delay: 5000, initialClock: 360000});reauth.advance(5000);assert.equal(reauth.posts(), 1);
    assert.equal(page(store, {stage: 'REAUTH:3:4'}).posts(), 0);
    assert.equal(page(store, {stage: 'REAUTH:6:7'}).posts(), 1);
    assert.equal(page(store, {stage: 'REAUTH:9:10'}).posts(), 0);
});
test('cumulative wait budget and malformed storage fail closed', () => {
    const store = new Map([['feature125:result-budget:receipt-1', JSON.stringify({count: 1, waited: 30000, stages: {}})]]);
    const p = page(store, {stage: 'REAUTH:3:4', delay: 1});p.advance(1);assert.equal(p.posts(), 0);
    assert.equal(page(new Map([['feature125:result-budget:receipt-1', 'invalid']])).posts(), 0);
});
