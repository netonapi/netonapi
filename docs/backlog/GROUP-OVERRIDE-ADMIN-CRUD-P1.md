# GROUP-OVERRIDE-ADMIN-CRUD-P1

## Status

**OPEN.** Deliberate intermediate state, recorded so it is not rediscovered as a surprise.

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
- write path: **none**

## Gap

There is no admin CRUD for this table. Operations cannot create, list, update, or revoke a
per-user exception through any interface, while the resolver already reads it and the delete guard
already protects it. Harness S28 has to insert the row with raw SQL to exercise the guard.

Two consequences worth stating plainly:

- The negotiated-price feature is unreachable in production without manual database access.
- Manual database access is exactly how a dangling `group_code` gets created, and a dangling
  `group_code` is a 500 for that user on every request. The missing CRUD is therefore not only a
  missing convenience, it is the missing half of the safety story.

The table is admin-writable by design and must never appear on an app-side route: a self-service
user who can set their own billing group can give themselves a discount. Harness S29 locks the
self-service side.

## Verification

- admin routes exist for create / list / update / delete, all in the admin route group only
- `group_code` is validated against live `gateway_groups` on write, so a dangling reference cannot
  be created through the API in the first place
- one user has at most one row (`userId` is the primary key); a second create for the same user is
  a 4xx conflict, not a 500
- harness replaces the raw SQL insert in S28 with an admin API call, and adds a case asserting the
  app route group exposes none of these routes
- `harness/run.sh` stays green
