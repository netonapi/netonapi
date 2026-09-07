# REDEMPTION-CODE-HASH-AT-REST-P2

## Status

OPEN. P2. This is no longer a hole on the API path — `RedemptionCodeLogic.redact()` closed that, and
harness S50 holds it closed. What is left is exposure **at rest**: `gateway_redemption_codes.code`
is a plaintext column, so a database dump, a replica, a backup file, or an injection anywhere else
in the schema yields every unused code, and each one is money.

Recorded here because the fix touches an applied migration's stated rationale, and that rationale
cannot be corrected in place.

## Standard: the same module already does this for API tokens

`module-gateway` is not missing a pattern to copy. Tokens have been hash-at-rest since the baseline:

- `gateway_tokens` carries `key_hash CHAR(64)` plus `key_display VARCHAR(32)`, with
  `uk_gateway_tokens_hash UNIQUE` on the hash. There is no plaintext column.
- `logic/TokenLogic.kt:69` — `hashKey(plaintext) = Sha256.hex(plaintext)`, from
  `neton.security.digest.Sha256`. That object lives in `neton-security/src/commonMain` and goes
  through cryptography-kotlin, so it is **dialect-agnostic**: the hash is computed in Kotlin, never
  in SQL, and does not depend on what the database engine happens to provide.
- `TokenLogic.verify` looks the row up by hash (`oneWhere { keyHash eq hashKey(plaintext) }`).
- The plaintext is handed out exactly once, in `IssuedTokenVO`, at issue time.
- `TokenLogic.display` is `take(7) + "****" + takeLast(4)` — keep the head and the tail, mask the
  middle. `redact()` is the same idea with the code's own group structure, chosen so support can
  line it up against what a customer reads over the phone.

Redemption codes are the only credential in this gateway stored in plaintext.

## Gap

`sql/postgresql/V004__redemption_codes.sql` stores `code VARCHAR(64) NOT NULL` with
`uk_gateway_redemption_code` on the plaintext, and `RedemptionCodeLogic.redeem` compares
`RedemptionCode::code eq normalize(rawCode)` directly.

The code body carries ~99 bit (20 characters over a 31-character alphabet). That defeats guessing,
which is why `redeem` needs no rate limit. It does nothing against reading: when the table is read,
the codes are in it. V004's own note ② claims entropy covers a leaked database. That claim is
false, and the same sentence in `model/RedemptionCode.kt` was corrected when `redact()` landed.

## One of the two wrong comments cannot be fixed

- `model/RedemptionCode.kt` — corrected. The "operators need to view a campaign's codes repeatedly
  in the admin" half was dropped: it turns "can read the list" into "can redeem any unused code in
  it", and `gateway:redemption:list` is exactly the permission a read-only audit role gets.
- `sql/postgresql/V004__redemption_codes.sql:13-16` (note ②) — still says both, and **must not be
  edited**. Applied scripts are frozen; the migrator compares SHA-256 of the raw bytes and treats a
  single changed byte as a changed script, so touching V004 makes every existing database refuse to
  start (`migrate status` exits 3, and the startup precheck aborts before binding a port). See
  `DEPLOY.md` §升级 for the mechanism and for why re-stamping the checksum is worse than the abort.

  Consequence for whoever implements this: the correction has to go in the header comment of the
  new V008 script, stating that it supersedes V004 note ②. A reader following the migration chain
  will otherwise take V004's word for it, and V004 is the only place the original reasoning is
  written down in full.

## Plan

**1. `sql/postgresql/V008__redemption_code_hash.sql`** (postgresql only — `DEPLOY.md:254` declares
PostgreSQL the sole supported dialect, and `sql/mysql/` and `sql/sqlite/` are historical leftovers
that must not be extended).

The slot number has moved twice, and it will probably move again. This item was written when V006
was free; `V006__quota_revoke_menu.sql` took it, and then `V007__billing_attribution.sql` took the
slot this item was next pointing at. That one shipped ahead of this item on purpose: it fixes a
live defect in the billing path (attribution was computed and then dropped), while this one hardens
a table that is already masked on every read path. Check `sql/postgresql/` for the next free slot
before writing the file — the migrator orders by version, and two scripts claiming the same one is
exactly the kind of collision that only surfaces on a database that already applied one of them.

- `ALTER TABLE gateway_redemption_codes ADD COLUMN code_hash CHAR(64);`
- Backfill: `UPDATE gateway_redemption_codes SET code_hash = encode(sha256(code::bytea), 'hex');`
  PostgreSQL has had a built-in `sha256(bytea)` since 11 (verified against the 16.9 this project
  develops on). That is what makes the backfill one statement instead of a program.

  **The condition that decides whether this works**: SQL must hash exactly what Kotlin hashes.
  `redeem` compares `normalize(raw)`, and the `code` column already holds normalized bare codes —
  uppercase, no separators, ASCII-only by construction of the alphabet — so
  `sha256(code::bytea)` and `Sha256.hex(normalize(raw))` agree. Any encoding or casing difference
  and every legacy code silently stops working, with the failure mode being "the customer's real
  code reports invalid". Do not settle this by reasoning; settle it with the assertion listed under
  Verification.
- `CREATE UNIQUE INDEX uk_gateway_redemption_code_hash ON gateway_redemption_codes(code_hash);`
  Full-table unique, no `WHERE deleted = 0`, for the reason V004 note ① gives: this table is money
  evidence and is deliberately not soft-deleted, because a partial unique index would let a disabled
  code's value be reissued — one code redeemable twice.
- `DROP INDEX uk_gateway_redemption_code; ALTER TABLE gateway_redemption_codes DROP COLUMN code;`

  `applyScriptAndRecord` runs a postgresql script inside a single transaction, so add + backfill +
  index swap + drop are all-or-nothing. No dual-lookup transition window is needed, and none should
  be built: a fallback that also tries the plaintext column leaves the plaintext column in place,
  which is the thing being removed.

**2. model / logic**

- `RedemptionCode`: `code` becomes `codeHash`, plus a `codeDisplay` column holding the `redact()`
  form. `/page` and `/disable/{id}` then return what is stored instead of deriving it from a
  plaintext the controller no longer has.
- `generate()`: insert `codeHash = Sha256.hex(code)` and `codeDisplay = redact(code)`; keep
  returning the plaintext in `GenerateRedemptionVO.codes`, once, the way `IssuedTokenVO` does.
- `redeem()`: `oneWhere { RedemptionCode::codeHash eq Sha256.hex(normalize(rawCode)) }`.
- Keep the length guard on the normalized input **before** hashing. An over-long input must still
  be a 400 "格式不正确"; hashing it first would turn that into a lookup miss reported as 404
  "无效", which loses the distinction S50 asserts on.

**3. The drop is irreversible.** An operator who did not export a batch before upgrading can never
retrieve it — that is the point of the change, but it lands on whoever is mid-campaign. The release
note has to say "export every unused batch first" ahead of the schema change, not after it.

## Rejected alternatives

- **Salt / HMAC.** The code body carries ~99 bit, so precomputed tables and reversal are both out of
  reach; a salt only adds something to store and to keep identical across nodes. Stay with plain
  SHA-256, the same discipline as tokens.
- **bcrypt / argon2.** Slow hashes exist for low-entropy human passwords. `redeem` is deliberately
  unthrottled — that is precisely what the 99 bit buys — so a slow hash there converts the cheapest
  endpoint in the product into a DoS amplifier.

## Verification criteria

- Generate a batch, then assert the row holds no plaintext: the `code` column is gone and
  `code_hash` equals the SHA-256 of the bare code `/generate` handed back.
- The `/generate` plaintext redeems; the `/page` string does not — 404, balance unmoved, no
  `type='redeem'` ledger row. That is S50 and it must stay green through the change.
- **The one that actually matters**: insert a code the old way as plaintext, run V008, then redeem
  it. A wrong backfill surfaces here and nowhere else, because everything written after the
  migration is hashed by the new code path.
- Cross-check the two implementations in the same run: `Sha256.hex(x)` in Kotlin against
  `encode(sha256(x::bytea), 'hex')` in psql, for the same `x`.

## Why P2 and not P1

`redact()` already removed the path where a read-only audit role could drain the pool through the
API. What remains needs a database-level compromise, which is access control's job to prevent. The
fix itself carries an irreversible column drop and requires a release-window announcement, so it
costs more operational risk than it removes until there is a window to spend.
