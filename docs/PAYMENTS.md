# ReelMint Pro: how payments work

ReelMint Pro is **one payment, for life** (default ₹50, `PRO_PRICE_PAISE`). Checkout is **Razorpay Standard Checkout**. This follows the design of Resume Coach's `PAYMENTS.md`, extended with a tested payment server and a restore flow.

## ⚠ Read this before publishing on Google Play

**Selling in-app unlocks through Razorpay instead of Google Play Billing can get the app rejected or removed.** Google Play's payments policy generally requires Play Billing for digital features unlocked inside an app. What I found when checking (I could **not** confirm anything specific to 2026, so verify on Google's current Payments policy page before you ship):

- Google ran a *user choice billing* pilot in India (announced 2022): developers can offer an alternative payment system **next to** Play Billing, and the user picks. It is not a way to replace Play Billing. ([Inc42](https://inc42.com/?p=354771), [Siasat](https://www.siasat.com/google-pilot-to-let-people-use-alternative-billing-in-play-store-now-in-india-2403621))
- Google still takes a service fee on those sales (reduced by 4 points when the user picks the alternative). ([TechCrunch](https://techcrunch.com/?p=2489922))
- Reported conditions: a registered business, PCI-DSS compliance, customer support and a grievance process for unauthorised transactions on the alternative channel. ([Electronic Payments International](https://www.electronicpaymentsinternational.com/?p=58698))

Practical options: (a) enrol in user choice billing and offer **both** Play Billing and Razorpay; (b) use Play Billing only (the payment layer is swappable: implement `PaymentLauncher` for Play Billing); (c) distribute outside Play (direct APK / other stores), where Razorpay is fine. Your own `claude.md` section 6 says the same.

## What Pro unlocks
One source of truth: `Entitlements` in `Pro.kt`. The pricing screen is generated from it, and a unit test checks the numbers.

| | Free | Pro |
|---|---|---|
| Ads | Banner on home, short ad after some exports | None, anywhere (the ads SDK is never even started) |
| "Made with ReelMint" tag | On video, slideshow and GIF exports (shown *before* exporting) | Never |
| Export quality | Small, Standard | + High (1080p) |
| Slideshow templates | 5 | 19 (14 Pro, each with its own pace, canvas, motion, transition) |
| Slideshow motions | 3 | 7 (+ Zoom out, Pan across, Drop in, Beat pulse) |
| Filters / video looks | 11 / 7 | 19 / 13 |
| Photo effects | 5 | 9 |
| Sticker packs | 1 (16) | 9 (about 150 stickers) |
| Collage layouts | 8 | 14 (up to 9 photos) |
| GIF maker | up to 5 s, Small/Medium, up to 15 fps | up to 10 s, Large, 20 fps, Reverse loop, 0.5× / 2× speed |
| Photo text fonts | 5 | 12 (Light, Thin, Heavy, Casual, Typewriter, Small caps, Slim) |
| Photo frames | Plain border | 5 styles (Polaroid, Film strip, Rounded, Shadow) |
| Video caption styles | 5 | 8 (Outline, Pink glow, Sunset) |
| Voice effects | 4 | 7 (Robot, Echo, Alien) |
| Slideshow transitions | 2 | 3 (Flash white) |
| Clip speed | 0.25× to 3× | 0.1× slow-mo to 8× timelapse |
| **Your own logo** | not available | on every video, slideshow and GIF, in any corner |
| App themes | 1 (Violet) | 6 (Ocean, Sunset, Forest, Rose, Graphite) |
| Saved photo looks | 3 | Unlimited |

Any Pro-only choice that is still set when a free user exports or saves (an old draft, a stale setting) is quietly replaced by its free equivalent (`ExportRules.kt`, unit-tested), so a free export can never contain a Pro-only result.

Editing, saving and sharing are **never** locked. To ship **without** the tag on free exports set `Entitlements.FREE_EXPORTS_CARRY_TAG = false`.

## Two modes (same as Resume Coach)

| | **App-only (current)** | **Server-verified (recommended for production)** |
|---|---|---|
| Turn on | `RAZORPAY_KEY_ID` | also `PAYMENT_API_URL` |
| Razorpay secret | not used anywhere | only on **your** server |
| Who sets the price | the app (`PRO_PRICE_PAISE`) | your server, per order |
| Unlock decision | the app trusts Razorpay's success callback | only after the server confirms the signature **and** the order's amount and product |
| Bypassable | yes, by someone who patches the app or has root | no, for payments |
| Restore on a new phone | Android backup only | backup **or** payment id |

Without a key id the purchase is switched off (the Pro screen says so and the promos are hidden); free limits still apply.

## Configure (never commit)
`local.properties` (git-ignored) or `-P` Gradle properties; see `local.properties.example`:
```
RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxx     # PUBLIC key id. rzp_test_ while testing, rzp_live_ for release
PAYMENT_API_URL=https://pay.example.com   # optional, https only (http://10.0.2.2:PORT for an emulator)
PRO_PRICE_PAISE=5000                      # optional, default ₹50
```
The **key secret never goes in the app or this repo**.

## The payment server (`server/`)
Zero-dependency Node 18+. `RZP_KEY_ID`, `RZP_KEY_SECRET` (and optionally `PRO_AMOUNT_PAISE`, `PORT`) as environment variables. See `server/README.md`. It:
1. creates the order at **its own** price (`/orders`),
2. verifies the HMAC signature in constant time, then fetches the order from Razorpay and requires `paid`, the right amount **and** `notes.product = pro_lifetime` (`/verify`). The product check matters if one Razorpay account serves several apps: a cheaper payment from another app (for example Resume Coach at ₹39) must not unlock Pro,
3. answers restore requests only for a captured, un-refunded Pro payment (`/restore`),
4. rate-limits, caps body size, never echoes internals or secrets.

If you turn the server on, **update the privacy policy and Data safety**: the app then sends the order id, payment id and signature to your server.

## What the app does around payments
- Opens checkout only from the Pro sheet; one payment unlocks everything.
- Grants Pro **only after confirmation**, never twice for one payment id, and never loses a paid-but-unconfirmed payment (kept and retried on the next start).
- Never shows raw Razorpay or server error text to people.
- Keeps only the payment id and date, in `pro.xml`, the **only** file in Android backup (`backup_rules.xml`, `data_extraction_rules.xml`). It survives "Clear cache", not "Clear data"/uninstall, and then backup restores it.
- Settings → "Reset Pro (test mode only)" exists only with a `rzp_test_` key; `Pro.reset()` refuses to run with a live key, so a real purchase can't be wiped.

## Testing
- **Unit tests** (`ProTest`, `ProContentTest`, `server/test`): entitlements, store, checkout options, every payment state (success, cancel, failure, duplicate callback, forged signature, unsigned, paid-but-offline then confirmed, restore), the HTTP client against a real localhost server, and the server's verification rules (`cd server && node --test`).
- **On-device tests** (`ProDeviceTest.kt`): callbacks → Pro, free vs Pro behaviour of every gate on real exported frames, UI flows.
- **Real Razorpay test-mode checkout** (done by hand on the emulator): the checkout opens with the right amount and name; a domestic test card (`5267 3181 8797 5449`, any future expiry, CVV `123`, Razorpay's "Success" bank page) returned success, Pro unlocked and the ads disappeared; backing out gave "Payment cancelled. You haven't been charged." Notes from that run: this test account **rejects international cards** (the `4111…` Visa test card failed with "International cards are not supported"), and in test mode real UPI apps don't work, so test builds show only typed-UPI and test-card options.
- **Not tested:** the server against Razorpay's real API (it is tested against a stub; it has not been deployed), the Failure bank page, UPI, live mode, refunds.

## Going live: a deliberate checklist
1. Razorpay account activated for live payments; decide Play Billing vs Razorpay (see the warning above).
2. Deploy `server/` on HTTPS with the **live** secret in env vars; set `PAYMENT_API_URL`.
3. Replace `rzp_test_` with `rzp_live_` in `local.properties`. Nothing switches automatically.
4. Do one real ₹50 payment with your own card, check it in the Razorpay dashboard (Orders and Payments, notes `product=pro_lifetime`), then refund it. Remember a refund does **not** revoke Pro on that phone.
5. Update the privacy policy placeholders, Data safety and the store listing; consider a webhook (`payment.captured`) on the server.
