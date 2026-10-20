# RECHARGE-CHECKOUT-UI-P1

## Status

OPEN. P1, and now **frontend-only**. The backend contract for self-service checkout is complete and
held by harness — including the order read-back that this item originally listed as the one missing
backend piece (see Gap 3, closed). A production NetonAPI deployment therefore still has **no way for
a user to pay**: quota reaches an account only through an operator's manual `gateway:quota:grant`, an
offline-sold redemption code, or the mock endpoint that `docker-compose.yml` deliberately does not
expose.

What is left is two decisions and one page. The decisions still do not belong in a frontend patch,
which is why this stays a backlog item rather than becoming a diff.

## What already exists — do not rebuild any of it

The checkout contract is not the gap, and it is worth stating precisely so nobody re-derives it:

- `POST /app/gateway/recharge` `{price, channelCode}` → `RechargeOrderVO`
  (`rechargeId, merchantOrderId, amountMicro, price, orderId, displayMode, payload, channelOrderNo`),
  in `controller/app/gateway/RechargeController.kt`.
- `GET /app/gateway/recharge/get/{rechargeId}` → `RechargeStatusVO`
  (`rechargeId, merchantOrderId, amountMicro, price, payStatus, paidAt, createdAt`), same file.
  `payStatus` is 0=waiting / 1=credited / 2=closed. Scoped by `identity.id`; another user's order and
  a nonexistent id both return **404 with a byte-identical message**, so the autoincrement `rechargeId`
  is not an existence oracle. The VO deliberately omits `payOrderId` (an internal FK into payment's
  table) and `payChannelCode` (the user picked it seconds ago). Harness S53 holds all of this,
  including that a *closed* order stays readable instead of being filtered into a 404.
- It takes `price` and **not** `amount`: quota is computed server-side from
  `NETONAPI_QUOTA_PER_PRICE_UNIT`. Accepting both would let the client set its own price.
- Both parameters are validated **before** the intent row is created, so a rejected attempt leaves no
  `gateway_quota_recharges` row. Harness S30 asserts this (`缺 channelCode → 400 且不留意图单`).
- `payment` has **no default channel** — `PayOrderLogic.resolveRoute` treats null as unusable, and the
  controller's own message says so: `must be a usable pay channel code; payment has no default
  channel`. A checkout UI must therefore present a channel picker; it cannot preselect one.
- `GET /app/pay/channel/list` → `List<AppPayChannelVO {code, method, displayMode, remark}>` in
  `module-payment`'s `controller/app/channel/PayChannelController.kt`. Its KDoc states the purpose:
  `method` drives icon and grouping, `displayMode` drives what to do with `payload`. The VO
  deliberately omits `config`, which holds merchant ids and keys.
- `enum class PayDisplayMode { REDIRECT_URL, QR_CODE, SDK_PARAMS }` in `module-payment`'s
  `channel/PayPlatform.kt`.
- Order → callback → ledger is closed and idempotent: harness S32 (concurrent/replayed callbacks
  credit exactly once) and S33 (a failed credit rolls back entirely — no "paid but no quota", no
  "paid but no ledger row").
- `client-payment/src/pages/billing.tsx` already renders balance, the ledger, redemption, and mock
  recharge. Checkout is the one thing its own KDoc defers: "接入真实支付后此处换成下单流程".

## Gap

**1. No checkout entry point anywhere in the user console.** Grepping every client module, the
console app, and the workspace packages for a real recharge call returns nothing but the mock one.

**2. ~~`netonapi-client/README.md` already promises it.~~ CLOSED, and it was worse than recorded.**
Line 3 did say users "领 API Key、看用量与账单、充值" — three of four real, 充值 not — and the README
is the first file a prospective operator reads. Fixing it also uncovered a second stale claim in the
same file: its 已知缺口 section still described `console.nav.ts` as an empty array after the nav had
been filled in and pinned by S52. Both are corrected now; the section states that the missing piece
is the checkout, and that the empty `roles`/`permissions` passed to `AppShell` is deliberate rather
than unfinished, so nobody "completes" it into a frontend permission gate that guards nothing.

**3. ~~No way to read an order back.~~ CLOSED.** This was the only backend gap and it is gone:
`GET /app/gateway/recharge/get/{rechargeId}` reports `payStatus` / `paidAt`, scoped to the caller.
The original complaint still describes exactly why it was needed — polling `usage/balance` cannot
distinguish "still waiting" from "callback failed" from "cleanup job closed it", and those three call
for different user actions (wait / contact the operator / order again). S53 asserts the read-back
agrees with the ledger and the balance instead of trusting `pay_status` alone: an implementation that
wrote the status correctly but never credited would otherwise sail through.

## Three things to settle before writing the page

**① Two of the three `displayMode` branches have no generic web implementation.**
`REDIRECT_URL` is one line (`payload` is the URL). `QR_CODE` needs a renderer, and the console's
dependencies contain no QR library. `SDK_PARAMS` means "hand these parameters to the platform's own
SDK", which differs per platform and has no universal web form.

The honest options are (a) implement `REDIRECT_URL` and **filter the other two out of the channel
picker**, so a user is never offered a method that then fails, or (b) add a QR dependency and cover
two of three. What must not ship is a picker that lists all channels and discovers the problem after
the user has chosen. Option (a) needs no new dependency, which matters because the pnpm store here
cannot currently install `apps/shell` offline (`@types/react-dom@19.25` is missing from it) — adding
a dependency is not a zero-risk act in this workspace.

Verifying that `REDIRECT_URL` really is "one line" turned up a fourth thing this decision has to
absorb: **`payload` is not guaranteed to be an absolute URL.** `AlipayPayPlatform.prepay` returns
`"${cfg.serverUrl}?$query"` (absolute), but `SandboxPayPlatform.prepay` returns
`"/app/pay/mock/checkout?merchantOrderId=…&channelCode=…"` — a site-relative path pointing at a
*backend* route. The console only reaches the backend through its BFF proxy at `/api/backend/<path>`,
so a bare `location.href = payload` sends the sandbox flow to the console's own origin and 404s.
Settling this means either normalising a relative payload against a configured backend base URL, or
accepting that sandbox channels are unreachable from the console and filtering them out as well —
which would leave a fresh deployment with an **empty picker**, since `sandbox_alipay` is the only pay
channel seeded anywhere (payment `V001__baseline.sql`). An empty picker is honest; a picker whose one
entry 404s is not.

**② An unconfigured exchange rate reaches the user as an incomprehensible 500.**
`QuotaRechargeLogic.quotaPerPriceUnit()` signals via `error(...)`, i.e. `IllegalStateException`, which
the framework's catch-all turns into `500 Internal Server Error` with **the message not in the
envelope**. The real reason lands asynchronously in `infra_api_error_logs` — S30 asserts both halves.
That is the right behaviour for an operator and a dead end for a payer: the frontend literally cannot
translate it, because the reason never reaches the client.

So checkout forces a choice: either keep 500 and have the UI say something bland ("充值暂不可用，请
联系客服"), or reclassify "recharge is not configured" as a 4xx with a readable message. Note the
distinction from the two `IllegalArgumentException` cases already fixed this round — those were user
input errors and became 400. This one is a deployment error, and 5xx is arguably correct; what is not
correct is showing a payer the string "Internal Server Error".

**③ ~~The order read-back endpoint above has to exist first.~~ CLOSED** — it exists, so the page can
be built on a real completion state rather than on balance-polling.

## Plan

1. ~~`GET /app/gateway/recharge/{rechargeId}` (or `/status?merchantOrderId=`) returning `pay_status`,
   `amount_micro`, `paid_at` — scoped to `identity.id`, with a harness assertion on cross-user
   access.~~ **DONE.** Shipped as `GET /app/gateway/recharge/get/{rechargeId}` (`/get/{id}` is this
   module's idiom for read-by-id — `GroupController`, `PriceController`, `ChannelController` all use
   it). Cross-user access is S53 block C, and block D pins that the resulting 404 is
   indistinguishable from a nonexistent id. No `/page` list: credited recharges are already a
   `type='recharge'` ledger row whose `ref` carries the id, so history lives in the transaction page;
   a second list endpoint would give the same data two sources of truth.
2. Channel picker fed by `GET /app/pay/channel/list`, filtered per ① .
3. `displayMode` dispatch in `billing.tsx`, replacing the mock card rather than sitting next to it —
   the mock endpoint stays available for integration testing but should not be the primary affordance
   in a page a payer reads.
4. Fix `netonapi-client/README.md` whether or not the rest ships. **DONE ahead of the rest**: line 3 no
   longer says 充值, and the 已知缺口 section now names the missing checkout instead of claiming the
   console nav is an empty array (that was fixed separately and the README had gone stale). A README
   that promises money movement the product cannot do is worse than one that lists less.

## Verification criteria

- The picker only ever offers channels the page can actually complete (assert against
  `displayMode`, not against a hardcoded list).
- Order → redirect → simulated callback → `pay_status` flips → balance and one `type='recharge'`
  ledger row. S32/S33 already hold the backend half; what is new is the read-back and the fact that
  the UI drove it.
- Replay the callback: still exactly one ledger row, and the read-back endpoint still reports paid.
- With `NETONAPI_QUOTA_PER_PRICE_UNIT` unset, the user sees the decided message from ②, not
  `Internal Server Error`.
- ~~Cross-user read-back returns 404/403, not another user's order.~~ Held by S53 block C. It is 404,
  not 403: 403 would confirm the order exists, and `rechargeId` is autoincrement, so that confirmation
  is a free enumeration oracle. Block D additionally pins that "not yours" and "no such order" return
  the identical message.

## Why P1

It blocks the revenue path. The README no longer overstates it (Gap 2, closed), so what remains is
plainly a missing feature rather than a lie: a deployment cannot take money without an operator in
the loop. Redemption codes give a real alternative for offline sales and campaigns — that is what they
were built for — but they are not self-service: every code needs a human to sell it and paste it in.
