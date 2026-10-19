const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('every return reason is a real selectable option', () => {
  const html = fs.readFileSync(path.join(__dirname, '../src/main/resources/shop/index.html'), 'utf8');
  const select = html.match(/<select id="reason"[\s\S]*?<\/select>/)[0];
  const values = [...select.matchAll(/<option\b[^>]*value="([^"]*)"[^>]*>[^<]*<\/option>/g)].map(match => match[1]);
  assert.deepEqual(values, ['', 'damaged', 'not-working', 'wrong-color', 'sand', 'other']);
  assert.doesNotMatch(select, /<\/option\s+\w/);
});

// Check customer-facing claims against saved business states without invoking a model.
function storefront() {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {textContent: '', innerHTML: '', hidden: false,
      removeAttribute(name) { delete this[name]; }, addEventListener() {}});
    return elements.get(id);
  };
  const document = {getElementById: element, querySelector: element, addEventListener() {}};
  const context = vm.createContext({document, navigator: {language: 'en-US'}, Intl, URL,
    window: {addEventListener() {}}, setTimeout, clearTimeout, AbortSignal});
  const source = fs.readFileSync(path.join(__dirname, '../src/main/resources/shop/store.js'), 'utf8');
  vm.runInContext(source.slice(0, source.lastIndexOf('(async function init()')), context);
  return {element, async load(response) {
    context.fetch = async () => ({ok: true, json: async () => response});
    await vm.runInContext('loadOrders()', context);
  }, render(c) {
    context.fixture = {orderId: 'order-jedi-49', dashboardUrl: 'http://localhost:8080/default/wfRun/fixture', ...c};
    return vm.runInContext('showTracking(fixture)', context);
  }};
}

test('a received request does not claim approval or delivery', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'QUEUED'}), false);
  assert.equal(ui.element('tracking-title').textContent, 'We’ve received your request');
  assert.equal(ui.element('progress').hidden, false);
  assert.match(ui.element('progress').innerHTML, /We’re checking/);
});
test('review stays pending without promising a replacement', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'WAITING_REVIEW', orderId: 'order-sith-299'}), false);
  assert.equal(ui.element('tracking-title').textContent, 'Your request is with our team');
  assert.equal(ui.element('tracking-item').textContent, 'Sith Lord Lightsaber');
});
test('rejection closes the request without implying fulfillment', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'REJECTED', lhAvailable: true, lhStatus: 'COMPLETED'}), true);
  assert.match(ui.element('tracking-copy').textContent, /No replacement was issued/);
  assert.equal(ui.element('progress').hidden, true);
});
test('delivery requires both terminal business state and confirmation', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'DELIVERED', replacement: {effectCount: 1}}), false);
  assert.doesNotMatch(ui.element('tracking-title').textContent, /has arrived/);
  assert.equal(ui.render({status: 'DELIVERED', replacement: {effectCount: 1}, delivery: {delivered: true}, lhAvailable: true, lhStatus: 'COMPLETED'}), true);
  assert.equal(ui.element('tracking-title').textContent, 'Your replacement has arrived');
});
test('a failed request with a persisted effect does not claim delivery', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'ESCALATED', replacement: {effectCount: 1}, lhAvailable: true, lhStatus: 'EXCEPTION'}), true);
  assert.match(ui.element('tracking-copy').textContent, /receipt is on file/);
  assert.match(ui.element('tracking-copy').textContent, /Delivery is not confirmed/);
});
test('workflow links cannot use script URLs', () => {
  const ui = storefront();
  ui.render({status: 'QUEUED', dashboardUrl: 'javascript:alert(1)'});
  assert.equal(ui.element('workflow-link').href, undefined);
});

test('a recorded replacement does not prematurely confirm delivery', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'WAITING_DELIVERY', replacement: {effectCount: 1}}), false);
  assert.doesNotMatch(ui.element('progress').innerHTML, /Delivery confirmed/);
  assert.match(ui.element('progress').innerHTML, /Waiting for delivery confirmation/);
});

test('developer evidence does not infer a successful native run from delivery', () => {
  const ui = storefront();
  ui.render({status: 'DELIVERED', delivery: {delivered: true}, lhAvailable: false});
  assert.match(ui.element('evidence-native').textContent, /Unavailable/);
  assert.equal(ui.element('evidence-tools').textContent, 'No tool reads recorded yet');
  assert.equal(ui.element('evidence-effect').textContent, 'No replacement receipt');
});

test('developer evidence uses recorded tool reads, policy and receipt', () => {
  const ui = storefront();
  ui.render({status: 'WAITING_DELIVERY', requestPublished: true, policy: 'REVIEW', reviewApproved: true,
    toolReads: [{tool: 'get_order'}, {tool: 'get_policy'}, {tool: 'get_order'}],
    replacement: {receiptId: 'replacement-fixture', effectCount: 1}, lhAvailable: true, lhStatus: 'RUNNING'});
  assert.equal(ui.element('evidence-tools').textContent, 'get_order · get_policy');
  assert.match(ui.element('evidence-policy').textContent, /reviewer approved/);
  assert.match(ui.element('evidence-effect').textContent, /replacement-fixture · 1 effect/);
  assert.equal(ui.element('evidence-native').textContent, 'RUNNING');
});


test('business delivery keeps polling until native completion is observed', () => {
  const ui = storefront();
  const delivered = {status: 'DELIVERED', delivery: {delivered: true}};
  assert.equal(ui.render({...delivered, lhAvailable: true, lhStatus: 'RUNNING'}), false);
  assert.equal(ui.element('tracking-title').textContent, 'Your replacement has arrived');
  assert.equal(ui.element('evidence-native').textContent, 'RUNNING');
  assert.equal(ui.render({...delivered, lhAvailable: false}), false);
  assert.equal(ui.render({...delivered, lhAvailable: true, lhStatus: 'COMPLETED'}), true);
  assert.equal(ui.element('evidence-native').textContent, 'COMPLETED');
});

test('terminal business failure waits for native outcome except failed ingress', () => {
  const ui = storefront();
  assert.equal(ui.render({status: 'ESCALATED', lhAvailable: true, lhStatus: 'RUNNING'}), false);
  assert.equal(ui.render({status: 'REJECTED', lhAvailable: false}), false);
  assert.equal(ui.render({status: 'INGRESS_FAILED', lhAvailable: false}), true);
});


test('configured Dashboard port loads even when orders are empty', async () => {
  const ui = storefront();
  await ui.load({orders: [], dashboardUrl: 'http://localhost:18080'});
  assert.equal(ui.element('dashboard-link').href, 'http://localhost:18080/');
  ui.render({status: 'QUEUED', dashboardUrl: 'http://localhost:18080/default/wfRun/custom'});
  assert.equal(ui.element('workflow-link').href, 'http://localhost:18080/default/wfRun/custom');
});

test('Dashboard and workflow links clear unsafe or stale destinations', async () => {
  const ui = storefront();
  await ui.load({orders: [], dashboardUrl: 'https://dashboard.example.test'});
  assert.equal(ui.element('dashboard-link').href, 'https://dashboard.example.test/');
  for (const dashboardUrl of ['javascript:alert(1)', 'data:text/html,unsafe', '/relative', '', undefined]) {
    await ui.load({orders: [], dashboardUrl});
    ui.render({status: 'QUEUED', dashboardUrl});
    assert.equal(ui.element('dashboard-link').href, undefined);
    assert.equal(ui.element('workflow-link').href, undefined);
  }
});
