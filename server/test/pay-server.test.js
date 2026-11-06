'use strict';
const test = require('node:test'), assert = require('node:assert/strict'), crypto = require('node:crypto');
const { createServer, PRODUCT } = require('../pay-server');

const SECRET = 'test_secret_not_real', ENV = { RZP_KEY_ID: 'rzp_test_unit', RZP_KEY_SECRET: SECRET, PRO_AMOUNT_PAISE: '5000' };
const sig = (o, p, s = SECRET) => crypto.createHmac('sha256', s).update(`${o}|${p}`).digest('hex');

const openServers = [];   // always closed after each test, so one failing test can never leave the run hanging
test.afterEach(async () => { for (const close of openServers.splice(0)) await close(); });

/** Starts the server with a fake Razorpay API: `razorpay` maps "METHOD /path" to a response. */
async function start(razorpay = {}, now) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    const key = `${init.method || 'GET'} ${url.replace('https://api.razorpay.com/v1', '')}`; calls.push({ key, init });
    const r = razorpay[key]; if (!r) return { ok: false, status: 404, json: async () => ({}) };
    return { ok: r.status === undefined || r.status < 400, status: r.status || 200, json: async () => r.body };
  };
  const srv = createServer({ env: ENV, fetchImpl, now });
  await new Promise(r => srv.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  const post = async (path, body) => { const r = await fetch(base + path, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) }); return { status: r.status, json: await r.json() }; };
  const close = () => new Promise(r => { srv.closeAllConnections(); srv.close(() => r()); }); openServers.push(close);
  return { post, calls, base, close };
}
const paidOrder = (extra = {}) => ({ status: 'paid', amount: 5000, currency: 'INR', notes: { product: PRODUCT }, ...extra });
const captured = (extra = {}) => ({ status: 'captured', amount: 5000, currency: 'INR', notes: { product: PRODUCT }, ...extra });

test('refuses to start without credentials', () => { assert.throws(() => createServer({ env: {} }), /required/); });

test('/orders creates an order at the SERVER price and returns only public data', async () => {
  const s = await start({ 'POST /orders': { body: { id: 'order_1', amount: 5000, currency: 'INR' } } });
  const r = await s.post('/orders', { appVersion: '1', amount: 1, product: 'cheap' });   // client-sent amount/product are ignored
  assert.equal(r.status, 200); assert.deepEqual(r.json, { orderId: 'order_1', amountPaise: 5000, currency: 'INR', keyId: 'rzp_test_unit' });
  const sent = JSON.parse(s.calls[0].init.body); assert.equal(sent.amount, 5000); assert.equal(sent.notes.product, PRODUCT);
  assert.ok(!JSON.stringify(r.json).includes(SECRET), 'the secret never leaves the server');
  assert.match(s.calls[0].init.headers.Authorization, /^Basic /); await s.close();
});

test('/orders reports an upstream failure as 502 (the app treats that as retryable)', async () => {
  const s = await start({ 'POST /orders': { status: 500, body: {} } }); assert.equal((await s.post('/orders', {})).status, 502); await s.close();
});

test('/verify accepts a genuine paid Pro order', async () => {
  const s = await start({ 'GET /orders/order_1': { body: paidOrder() } });
  const r = await s.post('/verify', { orderId: 'order_1', paymentId: 'pay_ABCDEFGH1', signature: sig('order_1', 'pay_ABCDEFGH1') });
  assert.deepEqual(r.json, { verified: true }); await s.close();
});

test('/verify rejects a forged or wrong-secret signature without even asking Razorpay', async () => {
  const s = await start({ 'GET /orders/order_1': { body: paidOrder() } });
  for (const bad of ['deadbeef', sig('order_1', 'pay_ABCDEFGH1', 'other_secret'), sig('order_2', 'pay_ABCDEFGH1')]) {
    assert.deepEqual((await s.post('/verify', { orderId: 'order_1', paymentId: 'pay_ABCDEFGH1', signature: bad })).json, { verified: false });
  }
  assert.equal(s.calls.length, 0); await s.close();
});

test('/verify rejects a real payment made for a different price or another app on the same account', async () => {
  const s = await start({ 'GET /orders/order_cheap': { body: paidOrder({ amount: 3900 }) }, 'GET /orders/order_other': { body: paidOrder({ notes: { product: 'premium_unlock' } }) }, 'GET /orders/order_unpaid': { body: paidOrder({ status: 'created' }) } });
  for (const id of ['order_cheap', 'order_other', 'order_unpaid']) assert.deepEqual((await s.post('/verify', { orderId: id, paymentId: 'pay_ABCDEFGH1', signature: sig(id, 'pay_ABCDEFGH1') })).json, { verified: false }, id);
  await s.close();
});

test('/verify: missing fields are 400, an unknown order is a definite no, Razorpay down is retryable', async () => {
  const s = await start({ 'GET /orders/order_5xx': { status: 503, body: {} } });
  assert.equal((await s.post('/verify', { orderId: 'o' })).status, 400); assert.equal((await s.post('/verify', { orderId: 1, paymentId: 2, signature: 3 })).status, 400);
  assert.deepEqual((await s.post('/verify', { orderId: 'order_none', paymentId: 'pay_ABCDEFGH1', signature: sig('order_none', 'pay_ABCDEFGH1') })).json, { verified: false });
  assert.equal((await s.post('/verify', { orderId: 'order_5xx', paymentId: 'pay_ABCDEFGH1', signature: sig('order_5xx', 'pay_ABCDEFGH1') })).status, 502); await s.close();
});

test('/restore says yes only for a captured, un-refunded Pro payment at the right price', async () => {
  const s = await start({ 'GET /payments/pay_GOODGOOD1': { body: captured() }, 'GET /payments/pay_FAILEDF01': { body: captured({ status: 'failed' }) }, 'GET /payments/pay_CHEAPCH01': { body: captured({ amount: 3900 }) },
    'GET /payments/pay_OTHERAP01': { body: captured({ notes: { product: 'premium_unlock' } }) }, 'GET /payments/pay_REFUNDE01': { body: captured({ amount_refunded: 5000 }) }, 'GET /payments/pay_NOTES0001': { body: captured({ notes: undefined }) } });
  assert.deepEqual((await s.post('/restore', { paymentId: 'pay_GOODGOOD1' })).json, { pro: true });
  for (const id of ['pay_FAILEDF01', 'pay_CHEAPCH01', 'pay_OTHERAP01', 'pay_REFUNDE01', 'pay_NOTES0001', 'pay_UNKNOWN01']) assert.deepEqual((await s.post('/restore', { paymentId: id })).json, { pro: false }, id);
  await s.close();
});

test('/restore validates the payment id before calling Razorpay (no path tricks)', async () => {
  const s = await start({});
  for (const id of ['', 'order_1', 'pay_', '../../orders', 'pay_a/b', 'pay_ABCDEFGH1?x=1', 42, null]) assert.equal((await s.post('/restore', { paymentId: id })).status, 400, String(id));
  assert.equal(s.calls.length, 0); await s.close();
});

test('rejects bad JSON, oversized bodies, wrong paths and wrong methods; /health works', async () => {
  const s = await start({});
  assert.equal((await s.post('/verify', '{nope')).status, 400); assert.equal((await s.post('/verify', JSON.stringify({ pad: 'x'.repeat(5000) }))).status, 413);
  assert.equal((await s.post('/admin', {})).status, 404); assert.equal((await fetch(s.base + '/verify')).status, 404); assert.equal((await (await fetch(s.base + '/health')).json()).ok, true); await s.close();
});

test('rate limits one client after 30 requests a minute', async () => {
  let t = 1_000_000; const s = await start({}, () => t);
  for (let i = 0; i < 30; i++) assert.notEqual((await s.post('/verify', {})).status, 429);
  assert.equal((await s.post('/verify', {})).status, 429); t += 61_000; assert.notEqual((await s.post('/verify', {})).status, 429); await s.close();
});

test('an internal error never leaks details', async () => {
  const s = await start({}); const srv = createServer({ env: ENV, fetchImpl: async () => { throw new Error('secret=' + SECRET); } });
  await new Promise(r => srv.listen(0, '127.0.0.1', r)); const base = `http://127.0.0.1:${srv.address().port}`;
  const orig = console.error; console.error = () => {};
  const r = await fetch(base + '/orders', { method: 'POST', body: '{}' }); const text = await r.text(); console.error = orig;
  assert.equal(r.status, 500); assert.ok(!text.includes(SECRET)); await new Promise(r => { srv.closeAllConnections(); srv.close(() => r()); }); await s.close();
});
