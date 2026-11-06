const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const root = 'src/main/resources/templates/';
const scripts = file => [...fs.readFileSync(root + file, 'utf8').matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const helper = () => scripts('fragments/order-edit-payment-continuation.html').join('\n');
function page({enabled = true, confirm = async () => {}, submitFails = false} = {}) {
    let submits = 0, confirmations = 0;
    const listeners = {}, click = {};
    const status = {textContent: ''}, error = {textContent: ''};
    const button = {disabled: false, addEventListener: (event, fn) => {click[event] = fn;}};
    const manual = {disabled: false};
    const form = {querySelectorAll: () => [manual], addEventListener: (event, fn) => {listeners[event] = fn;}};
    const context = {window: {}, document: {getElementById: id => ({'payment-continue': form, 'payment-continuation-status': status, 'payment-error': error, 'payment-submit': button})[id]},
        HTMLFormElement: {prototype: {submit: function () {if (submitFails) throw Error('private_error'); submits++;}}},
        PayjpPayments: () => ({widgets: () => ({createForm: () => ({mount() {}}), confirmPayment: async () => {confirmations++; return confirm();}})})};
    vm.createContext(context);
    if (enabled) vm.runInContext(helper(), context);
    return {context, status, error, button, manual, listeners, click, submits: () => submits, confirmations: () => confirmations};
}
test('return page submits at most once and never claims success', () => {
    const p = page();p.context.window.orderEditPaymentContinue();p.context.window.orderEditPaymentContinue();
    assert.equal(p.submits(), 1);assert.equal(p.manual.disabled, true);
    assert.match(p.status.textContent, /まだ完了していません/);
    let prevented = false;p.listeners.submit({preventDefault() {prevented = true;}});assert.equal(prevented, true);
});
test('a reload has its own one-shot guard; server idempotency remains necessary', () => {
    for (let i = 0; i < 2; i++) {const p = page();p.context.window.orderEditPaymentContinue();p.context.window.orderEditPaymentContinue();assert.equal(p.submits(), 1);}
});
for (const [name, confirm] of [['resolved', async () => {}], ['SDK exception/cancellation', async () => {throw Error('private_sdk_secret');}]]) {
    test('same-page Widget ' + name + ' continues without repeating confirmation', async () => {
        const p = page({confirm});vm.runInContext(scripts('orders/increase-payment.html').find(s => s.includes('const payments')), p.context);
        await p.click.click();await p.click.click();assert.equal(p.confirmations(), 1);assert.equal(p.submits(), 1);
        assert.doesNotMatch(p.status.textContent + p.error.textContent, /private_sdk_secret/);
    });
}
test('a redirect-pending confirmation does not start an extra same-page continuation', async () => {
    const p = page({confirm: () => new Promise(() => {})});vm.runInContext(scripts('orders/increase-payment.html').find(s => s.includes('const payments')), p.context);
    p.click.click();await Promise.resolve();assert.equal(p.confirmations(), 1);assert.equal(p.submits(), 0);
});
test('Feature124 Widget remains manual without the helper flag', async () => {
    const p = page({enabled: false});vm.runInContext(scripts('orders/increase-payment.html').find(s => s.includes('const payments')), p.context);
    await p.click.click();assert.equal(p.confirmations(), 1);assert.equal(p.submits(), 0);
});
test('synchronous navigation failure exposes manual fallback and never retries automatically', () => {
    const p = page({submitFails: true});p.context.window.orderEditPaymentContinue();p.context.window.orderEditPaymentContinue();
    assert.equal(p.submits(), 0);assert.equal(p.manual.disabled, false);assert.doesNotMatch(p.status.textContent, /private_error/);
    let prevented = false;p.listeners.submit({preventDefault() {prevented = true;}});assert.equal(prevented, false);
});
test('redirect return clears callback URL before the existing POST and never starts the SDK', () => {
    const p = page();
    const order = [];
    p.context.window.location = {pathname: '/orders/1/edit/receipt/payment/return'};
    p.context.history = {replaceState: (_state, _title, path) => {assert.equal(path, p.context.window.location.pathname);order.push('clean');}};
    const submit = p.context.HTMLFormElement.prototype.submit;
    p.context.HTMLFormElement.prototype.submit = function () {order.push('post');submit.call(this);};
    for (const script of scripts('orders/increase-payment-return.html')) vm.runInContext(script, p.context);
    assert.deepEqual(order, ['clean', 'post']);assert.equal(p.confirmations(), 0);assert.equal(p.submits(), 1);
});
test('manual continuation while Widget is pending suppresses a later automatic duplicate', async () => {
    let finish;
    const p = page({confirm: () => new Promise(resolve => {finish = resolve;})});
    vm.runInContext(scripts('orders/increase-payment.html').find(s => s.includes('const payments')), p.context);
    const authentication = p.click.click();
    p.listeners.submit({preventDefault() {assert.fail('first manual submission must be allowed');}});
    finish();await authentication;
    assert.equal(p.confirmations(), 1);assert.equal(p.submits(), 0);
});
