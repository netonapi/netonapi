# GROUP-OVERRIDE-ADMIN-CRUD-P1

## Status

**CLOSED.** Admin CRUD shipped, and the resolver's step 2 finally has end-to-end coverage.
What was found while closing it is listed under Follow-ups — two of those are still open.

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

Found while closing this item. The first two are open; the third shipped and is kept here as the
record of what it took.

1. **`userId` existence cannot be validated.** `module-gateway` does not see `member` at compile
   time, so `create` accepts any positive number. Typing 100001 instead of 10001 returns 200, the
   customer keeps being priced at `default`, and **neither side reports an error**. The only
   mitigation today is operational: read the row back via `/list` after writing. A real fix needs
   an assembly-layer port, the same shape as `MemberGroupPort`.
2. **Revoking loses the audit trail, and the ledger cannot fully replace it.** The delete is hard,
   so the negotiated price and the contract number in `remark` leave no trace in this table.
   `gateway_settlements.pricing_snapshot` freezes the `ratio` used per settlement, so "how much was
   charged at what multiplier" survives — but the snapshot records **neither the billing group code
   nor which resolution layer produced it** (token override / per-user override / member group /
   default), so "why this multiplier" does not. `gateway_usage_logs` has no group or ratio column
   at all. Either give this table a soft-delete column (same shape as `gateway_groups`) or write
   `GroupSource` into the snapshot.
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
