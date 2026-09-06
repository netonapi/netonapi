# INVITE-REWARD-UNBOUND-P2

## Status

**OPEN.** Not a defect — the contract explicitly allows this configuration. Recorded because the
referral loop is built end to end on the member side and then stops one binding short of doing
anything, which is easy to mistake for a working feature.

## Standard

`module-member` reports the fact that an invite succeeded and knows nothing about rewards. The
extension point is `port/MemberInviteRewardPort.kt`:

- `MemberInviteRewardEvent(inviterUserId, inviteeUserId, recordId, code, totalInvited)` — member
  reports the fact only. `recordId` is documented there as "下游发奖的幂等锚点".
- `MemberInviteRewardListener.onInviteReward(event)` — implemented by whichever business module
  wants to hand something out.
- `MemberInviteRewardRegistry(listeners)` — collected and bound by the application assembly layer.

Documented semantics, all of which constrain any implementation:

- fired **after** the invite-binding transaction commits, alongside `autoFriend`
- weak consistency: a reward failure must not roll back the registration
- one listener throwing is logged and does not affect the others
- each listener is responsible for its own idempotency

Current wiring:

- registry type: `port/MemberInviteRewardPort.kt:37`
- consumed at: `init/MemberRuntimeBootstrap.kt:45`, via `ctx.getOrNull(...)`
- handed to: `logic/MemberInviteLogic.kt:31` as `rewardRegistry: ... ? = null`
- dispatched by: `MemberInviteLogic.dispatchInviteReward`
- bound at: **nowhere**

## Gap

There is no `bind(MemberInviteRewardRegistry::class, ...)` in any Neton module repository or in
`newgate/application`. `getOrNull` therefore always returns null, the registry is always absent,
and `dispatchInviteReward` has nothing to iterate.

Consequences:

- Invites are recorded (`member_invite_records`) and the inviter's running total is maintained, but
  nothing is ever handed out. There is no incentive attached to inviting anyone.
- The invite-code UI surface exists and works, so from the outside the feature looks shipped. An
  operator who turns on invite codes gets tracking without rewards, and no log line says so — the
  contract treats "not assembled" as a valid configuration, and it is silent by design.
- For a gateway where quota is money, "invite someone, both sides get quota" is the cheapest
  acquisition channel available, and the whole member-side machinery for it is already written and
  tested. What is missing is one bridge and one bind.

This is P2 rather than P1 because nothing is broken and no money is at risk: the absent registry is
a documented state, not a dangling reference or a silent fallback. It is a feature that stops short,
not a defect.

## Implementation shape

Follow the two bridges that already exist in `application/src/commonMain/kotlin/bridge/`
(`PayRechargeOrderBridge`, `GatewayQuotaRechargePaidListener`) — the assembly layer is the only
place that may know both `member` and `gateway`.

- a `MemberInviteRewardListener` that calls `QuotaLogic.grant` for the inviter, and optionally for
  the invitee, so the reward is gateway quota
- `ref = "invite:{recordId}:inviter"` / `"invite:{recordId}:invitee"`. The ref is not optional here:
  the contract says reward failures do not roll back and listeners may run again, so idempotency has
  to come from `uk_gateway_quota_tx_ref`. Two roles on one record need two distinct refs, otherwise
  the invitee's grant collides with the inviter's and one of them silently never lands.
- amounts from environment variables that **default to 0 = no reward**. Defaulting to a positive
  amount would start granting quota on upgrade for every existing deployment, which is the one thing
  a reward path must never do by accident.
- the listener must not throw out of `onInviteReward` for expected conditions (amount is 0, user has
  no quota account yet): log and return, because a throw there is only noise next to the other
  listeners' results.

## Verification

- with reward amounts unset or 0, behaviour is byte-for-byte what it is today: invites are recorded,
  no quota moves, no ledger rows appear
- with amounts set, one successful invite produces exactly one ledger row per rewarded role, with the
  refs above, and the balance moves by the configured amount
- re-driving the same `recordId` produces no additional ledger row (this is the idempotency the
  contract delegates to the listener, so it has to be demonstrated, not assumed)
- a reward failure leaves the invite binding in place: the registration is not rolled back
- harness covers both the 0-amount default and the configured-amount path, and asserts ledger
  row count and balance rather than only the HTTP status
- `harness/run.sh` stays green
