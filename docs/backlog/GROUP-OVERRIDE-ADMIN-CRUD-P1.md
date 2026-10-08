# GROUP-OVERRIDE-ADMIN-CRUD-P1

## Status

**CLOSED.** Admin CRUD shipped, and the resolver's step 2 finally has end-to-end coverage.
What was found while closing it is listed under Follow-ups — one of those is still open.

## Standard

`gateway_group_overrides` is the per-user billing group exception ("negotiated price for a large
account"). It is step 2 of the resolution order in `BillingGroupResolver.resolve`:

1. token override `gateway_tokens.group_override`
2. **per-user override `gateway_group_overrides.group_code`**
3. member group mapping `gateway_groups.member_group_id`
4. fallback group `gateway_groups.code = 'default'`

Every layer that hits must resolve to a live `gateway_groups` row. A dangling reference is
rejected as `GroupReject.CONFIG_BROKEN` (HTTP 500) instead of falling back, so a stale row in this
table turns into a hard outage for that one user rather than a silent pricing change.

Current coverage:

- schema: `sql/{postgresql,mysql,sqlite}/V002__group_billing_identity.sql`
- model and table object: `model/GroupOverride.kt`, `table/GroupOverrideTable.kt`
  (natural primary key `userId`, `@Id(autoGenerate = false)`)
- read path: `BillingGroupResolver.resolve` step 2
- delete guard: `GroupController.delete` refuses to delete a group still referenced here (409),
  covered by harness S28
- write path: `controller/admin/group/GroupOverrideController.kt` — list / get / create / update /
  delete, all five behind `@Permission("gateway:group_override:*")` and all five in the admin route
  group only

## Gap

Recorded as it was before this item closed; kept because the reasoning is what makes the follow-ups
legible.

There was no admin CRUD for this table. Operations could not create, list, update, or revoke a
per-user exception through any interface, while the resolver already read it and the delete guard
already protected it. Harness S28 had to insert the row with raw SQL to exercise the guard.

Two consequences worth stating plainly:

- The negotiated-price feature was unreachable in production without manual database access.
- Manual database access is exactly how a dangling `group_code` gets created, and a dangling
  `group_code` is a 500 for that user on every request. The missing CRUD was therefore not only a
  missing convenience, it was the missing half of the safety story.

The table is admin-writable by design and must never appear on an app-side route: a self-service
user who can set their own billing group can give themselves a discount. Two separate things are
locked, by two separate scenarios: S29 covers the **token** self-service path (injecting
`groupOverride` into `POST /app/gateway/token/create` writes nothing), and S48's last block covers
the override table itself (create / list / delete under `/app` are all 404).

## Verification

What actually ran, not what was planned:

- `harness/run.sh` **74 passed / 0 failed**, zero warnings, no leftover processes.
- **S48** (new) is the real test. Its headline assertion is not "the endpoint returns 200" but
  **the ratio actually changes**: baseline relay through `default` (ratio 1.0) charges 7500; after
  an admin creates an override to `vip48` (ratio 3.0) the same relay charges 22500 — asserted as
  `with == base * 3`, a ratio rather than a hardcoded amount, because a hardcoded amount would
  report "pricing changed" as "override did not take effect". This is the first end-to-end coverage
  of resolution step 2: before S48 the whole of `run.sh` touched `gateway_group_overrides` only in
  two raw-SQL lines inside S28, and no scenario ever asserted that configuring an exception changes
  what a customer is charged.
- S48 also covers: dangling group rejected 400 (not 500) before reaching the driver, blank code
  400, 257-char remark 400, `userId=0` 400, and zero rows written by any of them; duplicate create
  is **409 not 500** and does not trample the existing row; `update` is the only way to change it
  and pricing follows back to baseline; delete is a **hard delete** (the table has no `deleted`
  column and the model has no `@SoftDelete`, unlike `GatewayGroup`), a second delete is 404; and
  the app route group exposes none of these — 404, not 403, because 403 would tell an attacker the
  path is correct and they are merely unauthorized.
- **Mutation-verified.** Two guards removed at once: the liveness check in `requireLiveGroupCode`,
  and both duplicate checks in `create`. Result **71 passed / 3 failed**, exactly as predicted and
  nothing else: dangling group now returns 200 and really writes a row for user 2 (body
  `{"code":0,"message":"OK","data":2}`) while the blank / over-long / non-positive guards still
  return 400; duplicate create now returns 500 — `SqlxTableAdapter.insert` emits a bare
  `INSERT INTO ... VALUES` with no `ON CONFLICT`, so with both guards gone the primary-key
  violation propagates unclassified. The 3x pricing block stayed green, confirming the mutations
  hit the guards and not the feature. Restored byte-identically (`shasum -c` OK) and re-ran to
  74 / 0.
- **S28 rewritten, because it was a false green.** `GroupController.delete` checks references in
  the order token override -> per-user override -> channel CSV, and the first match wins. S27 set
  the harness token's `group_override` to `vip26` and never cleared it, so the probe intended for
  the per-user override was answered by the *token* check. Deleting the entire per-user override
  `if` block left S28 green. Each of the three probes now leaves exactly one reference in place
  and asserts the 409 message **names** that reference (`per-user override` / `token group
  override` / `channel(s)`). The status code alone cannot isolate them; the message can. S28 also
  creates its reference through the admin API instead of raw SQL, which is what the original
  Verification list asked for.

## Follow-ups

Found while closing this item. All three shipped and are kept here as the record of what each took;
the second left a narrower gap behind, which is item 4.

1. ~~**`userId` existence cannot be validated.**~~ **CLOSED.** The original complaint: `module-gateway`
   does not see `member` at compile time, so `create` accepted any positive number — typing 100001
   instead of 10001 returned 200, the customer kept being priced at `default`, and **neither side
   reported an error**. The only mitigation was operational (read the row back via `/list`).

   Shipped as `logic.MemberDirectoryPort` + the `MemberDirectory` binding point, wired in
   `GatewayRuntimeBootstrap` and implemented in the assembly layer's `Main.kt` — the same shape as
   `MemberGroupPort`, exactly as this item predicted. `create` now returns 400 naming the id.
   Harness S54 holds it (five blocks). Three things learned while closing it, all of which would
   have let a plausible-looking implementation pass:

   - **"The row is there" is not "an override may hang on it".** member's self-service deletion
     (`MemberAuthLogic.deleteOwnAccount`) sets `status = DELETED` and **does not touch the `deleted`
     column** — its own KDoc says the record is kept so the admin console can still see that this
     person closed their account. So `MemberTable.get(id) != null` is true for a deleted member.
     The judgement has to be `status == NORMAL`, which is also what member's `MemberStatus` KDoc
     demands in red ink (a negative test like `!= DELETED` silently admits whatever state gets
     added next — that is how `DELETED` was missed in the first place). S54 block C asserts the row
     really is retrievable with `deleted=0` before asserting the 400, so the block cannot pass by
     accident; block D covers `DISABLED`.
   - **Validation hangs on `create` only.** `update` and `delete` operate on a row that already
     exists, and they must keep working after that member closes their account: deleting the
     override is precisely what an operator wants to do then. Gating `delete` on current status
     would make the row undeletable forever. S54 block E pins this (a hand-inserted override for a
     deleted member still deletes with 200).
   - **It silently ate S48's coverage, and only reading the code showed it.** Three of S48's
     write-validation shots used `userId: 2`, which did not exist in `member_users`. They still
     returned 400 with zero rows written — but the reason moved from "dangling/blank/overlong group
     code" to "no such member", and the status code and row count are identical. Same shape as the
     S28 false green. Fixed two ways: S48's seed now inserts `member_users(2)` with `status=1`
     (explicitly, because the DDL default is 0=DISABLED while the Kotlin model default is
     1=NORMAL), and its assertion now requires the message to name `ghost48`.

   Unbound port degrades to the old behaviour (positive-number check only) with a `warn` line at
   startup, rather than failing to boot or refusing all writes — the trade-off is recorded in
   `MemberDirectory`'s KDoc. NetonAPI's assembly layer always binds it.
2. ~~**Revoking loses the audit trail, and the ledger cannot fully replace it.**~~ **CLOSED for the
   attribution half.** The original complaint: the delete is hard, so the negotiated price and the
   contract number in `remark` leave no trace in this table.
   `gateway_settlements.pricing_snapshot` freezes the `ratio` used per settlement, so "how much was
   charged at what multiplier" survives — but the snapshot records **neither the billing group code
   nor which resolution layer produced it** (token override / per-user override / member group /
   default), so "why this multiplier" does not. `gateway_usage_logs` has no group or ratio column
   at all. Either give this table a soft-delete column (same shape as `gateway_groups`) or write
   `GroupSource` into the snapshot.

   Opening it showed the gap was worse than described, and that it was never a missing feature — it
   was a half-finished one. `GroupSource`'s own KDoc opens with "goes into the log: source is the
   first thing a wrong-bill investigation asks for", and `resolve` returns it from all four layers.
   It was dropped at the single place that consumed the result: `RelayEngine` wrote
   `is GroupResolution.Resolved -> r.group`. The group *code* fared worse — it was threaded through
   four signatures (`execute` / `streamWithPreflight` / `bill` / `recordLog`) and used by none of
   them. `recordLog` took `group: String` and built `UsageLog` without it, because the table had no
   such column. **Kotlin does not warn on unused function parameters** (only unused locals), so
   neither ever fired. The unused parameter is the evidence: someone meant to log attribution and
   stopped halfway.

   Shipped as `V007__billing_attribution.sql` — `group_code` + `group_source` on **both**
   `gateway_settlements` and `gateway_usage_logs`. Harness S55 (four blocks). Three decisions worth
   keeping, because each rejects something that looks simpler:

   - **New columns, not the snapshot this item suggested.** `snapshotPrice` validates strictly: any
     missing track field returns null, and `bill` then routes the settlement to `MANUAL_REVIEW`
     **keeping the reserved funds** (V004 §7.1, billing is not allowed to guess a price). Adding a
     required field to the snapshot JSON would have made every in-flight old snapshot unparseable at
     the moment of upgrade and parked real money in manual review. Nullable columns never touch that
     path. They also survive T2: `markUpstreamStarted` rewrites the snapshot to fold in the channel
     discount and must not have to restate attribution, because the group is resolved once at the
     entry and does not change across channel retries.
   - **Both tables, because `finalize` reads only the settlement row.** It builds the entire
     usage_log from `s` — deliberately, since a worker replay may be another process with nothing in
     memory. Attribution that is not on the settlement row does not survive replay. S55 asserts both
     sides so a break names which half of the chain died: mutations M3 (finalize stops transcribing)
     and M4 (reserve stops persisting) both land on 100 / 3, for different reasons, and only the
     settlements-side assertion tells them apart.
   - **Nullable, and not backfilled.** An old row's group is unrecoverable — the group may have been
     renamed or deleted, the ratio may have changed since. Filling `'default'` would turn "unknown"
     into "it went through the default group", exactly the silent fallback V002 removed when it
     deleted `?: "default"` and `?: "1.0"`.

   The operational payoff lands in `bill`'s margin-inversion warning, which printed `ratio` and
   `costDiscount` but not the group: "ratio=3.0 inverted" could not distinguish a mis-set negotiated
   price from a mis-set standard price. It now carries `group=$group/${groupSource.name}`. What
   remains of the original complaint is item 4.
3. **CLOSED — the five `gateway:group_override:*` permissions had no `system_menus` seed**, so per
   rbac-spec section 1.4 they could not be granted to any role; only `super_admin`'s `*:*:*`
   passed. Not specific to this feature: 17 gateway permissions were in the same state.

   What it took, because the three parts only work together:

   - `sql/postgresql/V005__gateway_permission_menus.sql` seeds all 17 (3 second-level menus + 14
     buttons). PostgreSQL only, deliberately: the `sql/mysql/` and `sql/sqlite/` trees are dead
     weight that never runs, and their V001 still pins menu ids — V001's own comment records the
     700-704 collision with privchat that `ON CONFLICT (id) DO NOTHING` swallowed silently.
     Parent lookup uses **scalar subqueries, not JOINs**: a JOIN inserts zero rows when the parent
     is missing, so the migration reports success while the permission stays ungrantable — the
     exact silent failure this migration exists to remove. A scalar subquery yields NULL and trips
     `parent_id NOT NULL`; an ambiguous parent raises "more than one row returned by a subquery".
     Both are loud.
   - Three pages in `neton-application-front-gateway` plus their `manifest.ts` keys. `component` is
     load-bearing: the shell's catch-all uses it to look up `pageRegistry`, so a menu without a
     page renders "页面未安装". Seeding menus without pages would have traded an ungrantable
     permission for a dead menu item.
   - **S49** in the harness, so the gap cannot silently reopen. Three assertions: every
     `@Permission` string in module-gateway has a menu seed (33 vs 33, gap 0); every gateway menu
     `component` has a manifest key (8 vs 8, dangling 0); every gateway menu is `status=1`.

   S49 exists because of a fact worth writing down: **the hole was invisible to all 48 behavioural
   scenarios and always would have been.** `PermissionLogic` returns `setOf("*:*:*")` for
   super_admin straight from the role code, without consulting menus, and the harness runs as
   super_admin. Every endpoint returned 200. Only counting annotations against seeds shows it.
   The third assertion is not cosmetic either — `PermissionLogic` filters `Menu::status eq 1` when
   collecting permissions and the frontend's `buildRouteMap` skips `status !== 1`, while the column
   defaults to **0**: omit that one literal and the menu is in neither the sidebar nor the route
   table, with no error anywhere.

   Mutation-verified: dropping one lvl3 seed, one manifest key, and flipping the three lvl2 menus to
   `status=0` turned exactly S49's three assertions red (74 / 3), each naming the thing it lost.
4. **OPEN (narrow, spun out of item 2): the hard delete still loses `remark`.** Attribution means a
   revoked exception no longer erases *what the customer was billed at and why* — every settlement
   and usage row from that period now carries the group and the layer that produced it. What it does
   not preserve is `remark`, where the contract number lives. After a revocation, "which agreement
   gave this customer this price" is still unanswerable from the database: the rows say
   `vip55 / USER_OVERRIDE` and nothing about the deal behind it. The fix item 2 originally proposed
   still stands — a `deleted` column here, same shape as `gateway_groups`. Ranked below the
   attribution work because it is a records question, not a billing one: nothing is charged
   incorrectly while it stays open.
