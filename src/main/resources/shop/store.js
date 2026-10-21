'use strict';
const $ = id => document.getElementById(id);
const money = amount => new Intl.NumberFormat(navigator.language, {style: 'currency', currency: 'USD'}).format(amount);
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const orderNumber = order => /299$/.test(order.orderId) ? 'DS-1299' : 'TK-1049';
const picture = order => order.orderId === 'order-49' ? '/shop/grinder.svg' : order.orderId === 'order-299' ? '/shop/espresso.svg' : /299$/.test(order.orderId) ? '/shop/sith-saber.svg' : '/shop/jedi-saber.svg';
function savedExchange(orderId) {
  try {
    const id = localStorage.getItem('outer-rim.exchange.' + orderId);
    return /^[a-zA-Z0-9_-]{1,100}$/.test(id || '') ? id : null;
  } catch (_) { return null; }
}
function rememberExchange(c) {
  try { localStorage.setItem('outer-rim.exchange.' + c.orderId, c.requestId); } catch (_) { /* The tracking URL also persists without browser storage. */ }
}
let orders = [], selectedOrder, requestId, timer, submitting = false, lastAnnouncement = '', submission;
const dialog = $('exchange-dialog');

async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {'Content-Type': 'application/json'}, signal: AbortSignal.timeout(20000)});
  const body = await response.json();
  if (!response.ok) {
    const error = new Error(body.error || 'The service could not complete this request.');
    error.status = response.status;
    throw error;
  }
  return body;
}

function setDashboardLink(id, value) {
  const element = $(id);
  element.removeAttribute('href');
  try {
    const link = new URL(value);
    if (['http:', 'https:'].includes(link.protocol)) element.href = link.href;
  } catch (_) { /* An invalid or unavailable URL must not point to another stack. */ }
}

async function loadOrders() {
  try {
    const response = await api('/api/orders');
    orders = response.orders;
    setDashboardLink('dashboard-link', response.dashboardUrl);
    if (!orders.length) { $('orders').textContent = 'You don’t have any orders yet.'; return; }
    $('order-count').textContent = `${orders.length} orders`;
    $('orders').innerHTML = orders.map(order => `<article class="order">
      <div class="order-top"><div><span>Order </span><strong>#${orderNumber(order)}</strong></div><span class="delivered">Delivered</span></div>
      <div class="product-stage"><img src="${picture(order)}" alt="${escapeHtml(order.item)} with an illuminated blade" width="480" height="360"><span class="color-name">${/299$/.test(order.orderId) ? 'Crimson kyber · Dark side edition' : 'Green kyber · Light side edition'}</span></div>
      <div class="order-body"><div class="product-heading"><h2>${escapeHtml(order.item)}</h2><span class="price">${money(order.price)}</span></div><p class="product-description">${/299$/.test(order.orderId) ? 'Unlimited power. Limited warranty.' : 'Kyber included. Jedi Council approval sold separately.'}</p><div class="item-meta"><span>Quantity: 1</span><span>Replacement eligible</span></div>${savedExchange(order.orderId) ? `<a class="secondary exchange-link" href="/?request=${savedExchange(order.orderId)}">View exchange</a>` : `<button class="secondary" data-order="${escapeHtml(order.orderId)}">Return or replace item</button>`}</div>
    </article>`).join('');
  } catch (error) {
    $('orders').textContent = '';
    $('orders-error').textContent = 'We couldn’t load your orders. Refresh this page to try again.';
    $('orders-error').hidden = false;
  }
}

$('orders').addEventListener('click', event => {
  const button = event.target.closest('button[data-order]');
  if (!button) return;
  selectedOrder = orders.find(o => o.orderId === button.dataset.order);
  $('exchange-form').reset();
  updateResolution();
  $('form-error').hidden = true;
  $('form-item').textContent = selectedOrder.item;
  $('form-order').textContent = `Order #${orderNumber(selectedOrder)} · ${money(selectedOrder.price)}`;
  $('form-image').src = picture(selectedOrder);
  dialog.showModal();
});

function closeDialog() {
  if (submitting) return;
  const hasDraft = $('message').value.trim() || $('reason').value;
  if (hasDraft && !confirm('Discard this exchange request? Your request has not been sent.')) return;
  dialog.close();
}
$('close-dialog').addEventListener('click', closeDialog);
$('cancel-exchange').addEventListener('click', closeDialog);
dialog.addEventListener('cancel', event => { event.preventDefault(); closeDialog(); });
window.addEventListener('beforeunload', event => {
  if (dialog.open && !submitting && ($('message').value.trim() || $('reason').value)) event.preventDefault();
});

function updateResolution() {
  const information = $('info-resolution').checked === true;
  $('reason').required = !information;
  $('reason').disabled = information;
  $('message').required = information;
  $('submit-exchange').textContent = information ? 'Ask about this order ↗' : 'Request replacement ↗';
}
$('exchange-form').addEventListener('change', updateResolution);

$('exchange-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (submitting) return;
  const reason = $('reason').value;
  const information = $('info-resolution').checked === true;
  if (information && !$('message').value.trim()) { $('message').reportValidity(); return; }
  if (!information && !reason) { $('reason').reportValidity(); return; }
  const descriptions = {damaged: 'arrived damaged in transit', 'not-working': 'will not ignite or work as expected', 'wrong-color': 'arrived with the wrong blade color', sand: 'arrived damaged with sand jammed in the hilt', other: 'needs support'};
  const message = information ? `Question about my ${selectedOrder.item} order: ${$('message').value.trim()}` : `My ${selectedOrder.item} ${descriptions[reason]}. Please send a replacement. ${$('message').value.trim()}`;
  const scenario = /299$/.test(selectedOrder.orderId) ? 'review' : 'normal';
  // Retrying an uncertain response reuses the same business identity and payload.
  if (!submission || submission.message !== message || submission.scenario !== scenario) {
    submission = {requestId: crypto.randomUUID().replaceAll('-', ''), scenario, message};
  }
  submitting = true;
  $('submit-exchange').disabled = true;
  $('close-dialog').disabled = true;
  $('cancel-exchange').disabled = true;
  $('submit-exchange').textContent = 'Sending request…';
  $('form-error').hidden = true;
  try {
    const c = await api('/api/requests', {method: 'POST', body: JSON.stringify(submission)});
    requestId = c.requestId;
    const url = new URL(location.href); url.search = ''; url.searchParams.set('request', requestId);
    history.pushState({}, '', url);
    dialog.close();
    showTracking(c);
    $('tracking-title').tabIndex = -1; $('tracking-title').focus();
    window.scrollTo({top: 0, behavior: 'instant'});
    schedulePoll();
  } catch (error) {
    $('form-error').textContent = 'We couldn’t confirm your request. It may have been received. Please wait before trying again.';
    $('form-error').hidden = false;
    $('form-error').tabIndex = -1; $('form-error').focus();
  } finally {
    submitting = false;
    $('submit-exchange').disabled = false;
    $('close-dialog').disabled = false;
    $('cancel-exchange').disabled = false;
    updateResolution();
  }
});

function showTracking(c) {
  rememberExchange(c);
  $('orders-view').hidden = true; $('tracking-view').hidden = false;
  const delivered = c.status === 'DELIVERED' && c.delivery?.delivered === true;
  const replaced = Boolean(c.replacement);
  const answer = c.status === 'INFORMATION_SENT' && typeof c.customerAnswer === 'string' ? c.customerAnswer.trim() : '';
  const late = c.status === 'ESCALATED' && c.lateDelivery?.delivered === true;
  $('answer-panel').hidden = !answer; $('customer-answer').textContent = answer;
  const closed = ['REJECTED', 'ESCALATED', 'INFORMATION_SENT', 'INGRESS_FAILED'].includes(c.status);
  let title = 'We’ve received your request', copy = 'We’re checking your order and the details you shared. You can return to this page for updates.';
  if (c.status === 'WAITING_REVIEW' || c.status === 'REVIEW_SIGNALLED') {title = 'Your request is with our team'; copy = 'Your exchange needs a little extra review. There’s nothing else you need to do right now.';}
  if (replaced && !closed) {title = 'Your replacement is being delivered'; copy = 'Your replacement has been arranged. We’ll update this page when delivery is confirmed.';}
  if (delivered) {title = 'Your replacement has arrived'; copy = 'Delivery is confirmed. Your replacement is ready for its next adventure.';}
  if (c.status === 'REJECTED') {title = 'Your exchange wasn’t approved'; copy = 'Your request was reviewed and closed. No replacement was issued.';}
  if (c.status === 'ESCALATED') {title = 'Your request needs a closer look'; copy = replaced ? 'A replacement receipt is on file, but the request needs follow-up. Delivery is not confirmed.' : 'We couldn’t complete your exchange automatically. Your request has been saved for follow-up.';}
  if (late) copy = 'The simulated carrier confirmed delivery. Your request still needs follow-up; the workflow outcome is unchanged.';
  if (c.status === 'INFORMATION_SENT') {title = answer ? 'Here’s what we found' : 'Your request needs a closer look'; copy = answer ? 'Your answer is available below. No replacement was arranged.' : 'No answer was recorded. Your saved request needs follow-up.';}
  if (c.status === 'INGRESS_FAILED') {title = 'Your request couldn’t be sent'; copy = 'Your request is saved, but Kafka acknowledgement wasn’t confirmed. Keep this request’s link while we check it.';}
  $('tracking-title').textContent = title; $('tracking-copy').textContent = copy;
  $('status-icon').textContent = delivered ? '✓' : closed ? '!' : '↺';
  const order = c.order || orders.find(o => o.orderId === c.orderId) || {orderId: c.orderId, item: /299$/.test(c.orderId) ? 'Sith Lord Lightsaber' : 'Jedi Training Lightsaber', price: /299$/.test(c.orderId) ? 299 : 49};
  $('tracking-item').textContent = order.item;
  $('tracking-order').textContent = `Order #${orderNumber(order)} · Quantity: 1`;
  $('tracking-image').src = picture(order);
  $('replacement-price').textContent = money(order.price);
  const steps = [
    ['Request received', true, 'Your exchange request is saved.'],
    ['Exchange reviewed', replaced || delivered || c.status === 'REJECTED', replaced || delivered ? 'Your order and exchange details are checked.' : c.status === 'WAITING_REVIEW' ? 'Our team is reviewing your request.' : 'We’re checking your order and exchange details.'],
    ['Replacement arranged', replaced, replaced ? 'A replacement receipt is recorded.' : 'We’ll update you once your replacement is arranged.'],
    ['Replacement delivered', delivered, delivered ? 'Delivery confirmed.' : 'Waiting for delivery confirmation.']
  ];
  let next = steps.findIndex(s => !s[1]);
  $('progress').innerHTML = steps.map(([label, done, hint], i) => `<li class="${done ? 'done' : i === next && !closed ? 'current' : ''}" ${i === next && !closed ? 'aria-current="step"' : ''}><span class="step-dot" aria-hidden="true">${done ? '✓' : i + 1}</span>${label}${done || i === next && !closed ? `<small>${hint}</small>` : ''}</li>`).join('');
  if (closed) $('progress').hidden = true; else $('progress').hidden = false;
  $('tracking-note').textContent = delivered ? 'Your exchange is complete. No additional payment is due.' : closed ? 'Your saved request stays available at this page’s address.' : 'Keep this page’s address to check your exchange later. Updates appear automatically.';
  setDashboardLink('workflow-link', c.dashboardUrl);
  $('evidence-ingress').textContent = c.requestPublished ? 'customer-requests · acknowledged' : 'No Kafka acknowledgement recorded';
  const reads = [...new Set((c.toolReads || []).map(read => typeof read === 'string' ? read : read.tool || read.name))].filter(Boolean);
  $('evidence-tools').textContent = reads.length ? reads.join(' · ') : 'No tool reads recorded yet';
  $('evidence-policy').textContent = c.policy === 'AUTO' ? 'Java policy · automatic replacement' : c.policy === 'REVIEW' ? (c.reviewApproved === true ? 'Java policy · reviewer approved' : c.reviewApproved === false ? 'Java policy · reviewer declined' : 'Java policy · waiting for review') : c.policy || 'Not evaluated';
  $('evidence-effect').textContent = c.replacement ? `${c.replacement.receiptId || 'Receipt recorded'} · ${c.replacement.effectCount} effect` : 'No replacement receipt';
  $('evidence-delivery').textContent = late ? 'Late follow-up: simulated carrier delivery confirmed' : delivered ? 'Correlated delivery confirmed' : c.carrierPublished ? 'fulfillment-events · Kafka acknowledged' : 'No delivery event acknowledged';
  $('evidence-native').textContent = c.lhAvailable === true ? c.lhStatus : 'Unavailable · business state retained';
  if (lastAnnouncement !== title) { $('announcer').textContent = title; lastAnnouncement = title; }
  document.title = `${title} · Outer Rim Outfitters`;
  // Business delivery can precede the final worker acknowledgement in LittleHorse.
  const nativeTerminal = c.lhAvailable === true && ['COMPLETED', 'ERROR', 'EXCEPTION', 'TERMINATED'].includes(c.lhStatus);
  const pendingFollowUp = c.status === 'ESCALATED' && replaced && (c.followUpRequired === true || typeof c.carrierPublished === 'boolean') && !late;
  return c.status === 'INGRESS_FAILED' || ((delivered || closed) && nativeTerminal && !pendingFollowUp);
}

function schedulePoll() { clearTimeout(timer); if (requestId) timer = setTimeout(refreshStatus, 4000); }
async function refreshStatus() {
  if (!requestId || document.hidden) { schedulePoll(); return; }
  try {
    const c = await api(`/api/cases/${encodeURIComponent(requestId)}`);
    $('tracking-error').hidden = true; $('retry-status').hidden = true;
    if (!showTracking(c)) schedulePoll();
  } catch (error) {
    if (error.status === 404) {
      $('tracking-title').textContent = 'Exchange not found';
      $('tracking-copy').textContent = 'This exchange link is no longer available. Go back to your orders to request help.';
      $('progress').hidden = true;
      $('tracking-note').textContent = 'Check that you copied the complete exchange link.';
      document.querySelector('.summary').hidden = true;
      document.querySelector('.demo-details').hidden = true;
      return;
    }
    $('tracking-error').textContent = 'We can’t update your exchange right now. Your last confirmed status is shown. Check again in a moment.';
    $('tracking-error').hidden = false; $('retry-status').hidden = false;
    schedulePoll();
  }
}
$('retry-status').addEventListener('click', () => { clearTimeout(timer); refreshStatus(); });
document.addEventListener('visibilitychange', () => { if (!document.hidden && requestId) { clearTimeout(timer); refreshStatus(); } });
window.addEventListener('popstate', () => location.reload());

(async function init() {
  await loadOrders();
  const id = new URL(location.href).searchParams.get('request');
  if (!id) return;
  if (!/^[a-zA-Z0-9_-]{1,100}$/.test(id)) {
    $('orders-error').textContent = 'This exchange link isn’t valid. Choose an item from your orders below.'; $('orders-error').hidden = false; return;
  }
  requestId = id;
  $('orders-view').hidden = true; $('tracking-view').hidden = false;
  $('tracking-title').textContent = 'Loading your exchange…';
  $('tracking-copy').textContent = 'Checking the latest saved update.';
  await refreshStatus();
})();
