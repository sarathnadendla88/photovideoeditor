# Framora payment server

Reference implementation of the three endpoints the app calls (`/orders`, `/verify`, `/restore`). No dependencies, Node 18+. It holds the Razorpay **key secret**, so it is the only place the secret may live.

```
RZP_KEY_ID=rzp_test_xxx RZP_KEY_SECRET=xxx PRO_AMOUNT_PAISE=5000 PORT=8080 node pay-server.js
node --test        # unit tests, no network needed (Razorpay is stubbed)
```

| Endpoint | Body | Answer |
|---|---|---|
| `POST /orders` | `{}` (anything sent is ignored; the server decides the price) | `{orderId, amountPaise, currency, keyId}` |
| `POST /verify` | `{orderId, paymentId, signature}` | `{verified: true|false}`; 5xx/429 only for temporary problems (the app retries those) |
| `POST /restore` | `{paymentId}` | `{pro: true|false}` |
| `GET /health` | | `{ok: true}` |

Deploy it behind HTTPS (a small VM, Cloud Run, Fly.io, etc.). Not deployed yet. Put the secret in the host's secret store, never in git. See `docs/PAYMENTS.md`.
