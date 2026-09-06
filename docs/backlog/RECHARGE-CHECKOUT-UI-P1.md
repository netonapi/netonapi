# RECHARGE-CHECKOUT-UI-P1

## Status

OPEN. P1. The backend contract for self-service checkout is complete and already held by harness —
what is missing is the user-facing half. A production NanoGate deployment therefore has **no way for
a user to pay**: quota reaches an account only through an operator's manual `gateway:quota:grant`, an
offline-sold redemption code, or the mock endpoint that `docker-compose.yml` deliberately does not
expose.

Recorded separately from the redemption work because closing it needs one backend endpoint that does
not exist yet, plus a decision about a third-party dependency. Neither belongs in a frontend patch.

## What already exists — do not rebuild any of it

The checkout contract is not the gap, and it is worth stating precisely so nobody re-derives it:

- `POST /app/gateway/recharge` `{price, channelCode}` → `RechargeOrderVO`
  (`rechargeId, merchantOrderId, amountMicro, price, orderId, displayMode, payload, channelOrderNo`),
  in `controller/app/gateway/RechargeController.kt`.
- It takes `price` and **not** `amount`: quota is computed server-side from
  `NEWGATE_QUOTA_PER_PRICE_UNIT`. Accepting both would let the client set its own price.
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

**2. `newgate-client/README.md` already promises it.** Line 3 says users "领 API Key、看用量与账单、
充值". Three of those four are real; 充值 is not. The README over-promises relative to the code, and
it is the file a prospective operator reads first.

**3. No way to read an order back.** The app group exposes exactly two recharge endpoints — `POST ""`
and `POST /mock`. There is no `GET` for an order's `pay_status`. This is a backend gap, not a
frontend one, and it is what makes the redirect flow unusable in practice: the user leaves to pay,
comes back to a tab that has no idea whether the callback landed, and the only signal available is
polling `usage/balance` — which cannot distinguish "still waiting" from "callback failed" from
"cleanup job closed it". The cleanup half exists (`56db1db` binds `:waiting` so a failed order
actually closes); the read half does not.

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

**③ The order read-back endpoint above has to exist first**, otherwise the redirect flow has no
completion state and the page will be built on balance-polling, which cannot report failure.

## Plan

1. `GET /app/gateway/recharge/{rechargeId}` (or `/status?merchantOrderId=`) returning `pay_status`,
   `amount_micro`, `paid_at` — scoped to `identity.id` the way `usage/transaction/page` is, so one
   user cannot read another's order. Money-adjacent, so it needs the same treatment the other app
   endpoints get: harness assertion on cross-user access, not just a code review.
2. Channel picker fed by `GET /app/pay/channel/list`, filtered per ① .
3. `displayMode` dispatch in `billing.tsx`, replacing the mock card rather than sitting next to it —
   the mock endpoint stays available for integration testing but should not be the primary affordance
   in a page a payer reads.
4. Fix `newgate-client/README.md` whether or not the rest ships. If checkout slips, the word 充值
   comes out of line 3; a README that promises money movement the product cannot do is worse than one
   that lists less.

## Verification criteria

- The picker only ever offers channels the page can actually complete (assert against
  `displayMode`, not against a hardcoded list).
- Order → redirect → simulated callback → `pay_status` flips → balance and one `type='recharge'`
  ledger row. S32/S33 already hold the backend half; what is new is the read-back and the fact that
  the UI drove it.
- Replay the callback: still exactly one ledger row, and the read-back endpoint still reports paid.
- With `NEWGATE_QUOTA_PER_PRICE_UNIT` unset, the user sees the decided message from ②, not
  `Internal Server Error`.
- Cross-user read-back returns 404/403, not another user's order.

## Why P1

It blocks the revenue path, and the client README is already telling operators it works. Redemption
codes give a real alternative for offline sales and campaigns — that is what they were built for —
but they are not self-service: every code needs a human to sell it and paste it in.
