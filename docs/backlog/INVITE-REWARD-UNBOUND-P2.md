# INVITE-REWARD-UNBOUND-P2

## Status

**CLOSED.** It was not a defect — the contract explicitly allows this configuration. Recorded
because the referral loop was built end to end on the member side and then stopped one binding short
of doing anything, which is easy to mistake for a working feature.

Closed by `application/src/commonMain/kotlin/bridge/GatewayInviteRewardBridge.kt` and its bind in
`application/src/commonMain/kotlin/Main.kt`, an operator retry endpoint on the member side, and
harness scenarios S42–S45. Rewards stay off unless an operator turns them on. The verification
criteria this document listed are recorded below as results, not as intentions.

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
- bound at: `application/src/commonMain/kotlin/Main.kt`, next to the other cross-module bridges

The bridge follows the deferred-attach shape of `PayRechargeOrderBridge`: the instance is built and
bound during the DSL phase, then `attach(ctx)` runs inside the single existing `onStart`, because
`QuotaLogic` does not exist yet at bind time.

## Gap (as it was)

There was no `bind(MemberInviteRewardRegistry::class, ...)` in any Neton module repository or in
`newgate/application`. `getOrNull` therefore always returned null, the registry was always absent,
and `dispatchInviteReward` had nothing to iterate.

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

Followed the two bridges that already existed in `application/src/commonMain/kotlin/bridge/`
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

## Verification (results)

Harness scenarios S42–S45, each asserting ledger rows and balances rather than only HTTP status.
A successful invite is driven end to end over HTTP: `send-sms-code` then `sms-login` with an
`inviteCode`, which is the path that reaches `dispatchInviteReward`.

- **S42, amounts unset** — behaviour is what it was before the bridge existed: invite recorded,
  zero `invite:*` ledger rows, zero quota accounts for either user. It also proves the registry is
  actually bound rather than merely constructed: the listener logs one skip per role (+2), which is
  only possible if `member` received the registry.
- **S43, amounts set** (inviter 50000, invitee 20000) — one ledger row per role under the refs
  above, balances 50000 / 20000.
- **S44, replay** — `retry-invite-reward/{recordId}` returns 200, ledger stays at 2 rows, balances
  unchanged, and the idempotency-skip log goes up by exactly 2 (one per ref), so the replay really
  did reach the granting step instead of returning early. Unknown record → 400, no credentials → 401.
- **S45, malformed amount** (`INVITE_REWARD_INVITER=INVALID`) — that role grants nothing, logs
  `MISCONFIGURED`, and creates no quota account; the other role on the same record still receives
  20000. One bad value does not take the whole reward path down.
- **Binding survives a reward that does not happen** — demonstrated through S45 and S42: the invite
  record exists in every case even where no grant was made. Not demonstrated by injecting an
  exception out of `onInviteReward`; `dispatchInviteReward` already catches per listener and logs.

### What mutation testing changed

Green on the first run was not taken as evidence. Two mutations were applied to the finished work:

- **M1, delete the `bind` and keep `attach(ctx)`** — the historical shape of this gap. S42 as first
  written stayed green: the attach log still printed, and with amounts at 0 every ledger assertion
  was trivially satisfied. Only the listener-callback count caught it. S42 was strengthened because
  of this run, not before it.
- **M2, both roles share one ref** — the trap the bridge KDoc warns about. S43/S44/S45 went red with
  exactly the predicted symptom: 1 ledger row instead of 2, invitee balance 0 instead of 20000, and
  every HTTP status still 200. The second grant collides with the first role's sentinel row and is
  silently skipped.

Both mutations were reverted and the restored files verified byte for byte against a checksum taken
before mutating. `harness/run.sh` is green at 68 passed / 0 failed.
