'use strict';
/**
 * ReelMint payment server (reference implementation, zero dependencies, Node 18+).
 * It holds the Razorpay key SECRET, which must never be in the app, and answers three questions for the app:
 *   POST /orders   -> create a Razorpay order for ReelMint Pro (the SERVER decides the price)
 *   POST /verify   -> is this payment really paid, for this app and this price?
 *   POST /restore  -> same check starting from a payment id (new phone / reinstall)
 * Configure with environment variables only (never commit them):
 *   RZP_KEY_ID, RZP_KEY_SECRET, PRO_AMOUNT_PAISE (default 5000), PORT (default 8080)
 */
const http = require('node:http');
const crypto = require('node:crypto');

const PRODUCT = 'pro_lifetime';
const MAX_BODY = 4096;

function createServer({ env = process.env, fetchImpl = fetch, now = Date.now } = {}) {
  const keyId = env.RZP_KEY_ID, secret = env.RZP_KEY_SECRET;
  const amount = parseInt(env.PRO_AMOUNT_PAISE || '5000', 10);
  if (!keyId || !secret) throw new Error('RZP_KEY_ID and RZP_KEY_SECRET are required');
  const auth = 'Basic ' + Buffer.from(`${keyId}:${secret}`).toString('base64');
  const hits = new Map();   // ip -> [timestamps], a small in-memory rate limit

  const rzp = async (path, init = {}) => {
    const r = await fetchImpl('https://api.razorpay.com/v1' + path, { ...init, headers: { Authorization: auth, 'Content-Type': 'application/json' } });
    return { ok: r.ok, status: r.status, body: await r.json().catch(() => ({})) };
  };
  const send = (res, code, obj) => { const b = JSON.stringify(obj); res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b) }); res.end(b); };
  const limited = (ip) => { const t = now(), list = (hits.get(ip) || []).filter(x => t - x < 60_000); list.push(t); hits.set(ip, list); return list.length > 30; };

  const sameHmac = (a, b) => typeof a === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
  const validSignature = (orderId, paymentId, signature) =>
    sameHmac(signature, crypto.createHmac('sha256', secret).update(`${orderId}|${paymentId}`).digest('hex'));

  // A payment only counts when it is captured, for OUR price, and was made for THIS product. The product check matters when
  // one Razorpay account serves several apps: a cheaper payment from another app must not unlock Pro.
  const isProPayment = (p) => !!(p && p.status === 'captured' && p.amount === amount && p.currency === 'INR' && p.notes && p.notes.product === PRODUCT && !p.refunded_at && !(p.amount_refunded > 0));

  const handlers = {
    async '/orders'() {
      const r = await rzp('/orders', { method: 'POST', body: JSON.stringify({ amount, currency: 'INR', receipt: crypto.randomUUID().slice(0, 36), notes: { product: PRODUCT, app: 'ReelMint' } }) });
      if (!r.ok) return [502, { error: 'order_failed' }];
      return [200, { orderId: r.body.id, amountPaise: r.body.amount, currency: r.body.currency, keyId }];
    },
    async '/verify'(b) {
      const { orderId, paymentId, signature } = b;
      if (![orderId, paymentId, signature].every(v => typeof v === 'string' && v.length > 0 && v.length < 200)) return [400, { error: 'bad_request' }];
      if (!validSignature(orderId, paymentId, signature)) return [200, { verified: false }];
      const o = await rzp(`/orders/${encodeURIComponent(orderId)}`);
      if (!o.ok) return [o.status >= 500 ? 502 : 200, o.status >= 500 ? { error: 'upstream' } : { verified: false }];
      const ok = o.body.status === 'paid' && o.body.amount === amount && o.body.currency === 'INR' && o.body.notes && o.body.notes.product === PRODUCT;
      return [200, { verified: !!ok }];
    },
    async '/restore'(b) {
      const { paymentId } = b;
      if (typeof paymentId !== 'string' || !/^pay_[A-Za-z0-9]{8,30}$/.test(paymentId)) return [400, { error: 'bad_request' }];
      const p = await rzp(`/payments/${paymentId}`);
      if (!p.ok) return [p.status >= 500 ? 502 : 200, p.status >= 500 ? { error: 'upstream' } : { pro: false }];
      return [200, { pro: isProPayment(p.body) }];
    },
  };

  return http.createServer(async (req, res) => {
    try {
      if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true });
      const h = handlers[req.url];
      if (req.method !== 'POST' || !h) return send(res, 404, { error: 'not_found' });
      if (limited(req.socket.remoteAddress || 'x')) return send(res, 429, { error: 'slow_down' });
      const chunks = []; let size = 0;
      for await (const c of req) { size += c.length; if (size > MAX_BODY) return send(res, 413, { error: 'too_large' }); chunks.push(c); }
      let body; try { body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); } catch { return send(res, 400, { error: 'bad_json' }); }
      const [code, obj] = await h(body && typeof body === 'object' ? body : {});
      send(res, code, obj);
    } catch (e) { console.error('request failed:', e.message); send(res, 500, { error: 'server_error' }); }   // never echo internals or secrets
  });
}

module.exports = { createServer, PRODUCT };

if (require.main === module) {
  const port = parseInt(process.env.PORT || '8080', 10);
  createServer().listen(port, () => console.log(`ReelMint payment server on :${port}`));
}
