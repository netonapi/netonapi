# SQLX4K-VARIANT-SHARED-BUILD-DIR-P2

## Status

OPEN. P2. Not a product defect and not a mistake anyone made: two consumers of the same framework
checkout ask the build for different sqlx4k drivers, and the framework module they both compile
into has **one** output directory. Whoever writes last decides what the other one links.

`harness/run.sh` now detects and heals the result, so the cost on this machine dropped from "a full
harness run dies before scenario 1, with a hint that points at the wrong file" to "one extra
rebuild". The underlying hazard is still there and the fix belongs to the framework's build layout,
which is why this is written down instead of closed.

## What it looks like

The binary builds and links fine, then dies on the first database touch:

```
database connect failed: NETON-DB-VARIANT mismatch: 当前 build variant = sqlite, 但 config.driver = POSTGRESQL.
重新编译并 -Pneton.database.driver=postgresql 切换 variant, 或修改 database.conf 使用 SQLITE / MEMORY.
```

Both halves of that advice are already satisfied — the build *was* passed
`-Pneton.database.driver=postgres`, and `database.conf` *does* say `POSTGRESQL`. That is what makes
it expensive: the message reads like a configuration mistake, so the first instinct is to go read
`database.conf` and the build script, neither of which is wrong.

## Mechanism

1. `Neton/neton/neton-database/build.gradle.kts:7-27` reads the gradle property
   `neton.database.driver` (default `postgres`), then at `:37` adds `src/${driver}Variant/kotlin` to
   `commonMain` and at `:45` depends on exactly one sqlx4k artifact. The reason (`:8-10`) is real:
   each driver klib carries a full Rust runtime, and a K/N executable linking two of them dies in
   `ld.lld` with duplicate symbols. So the driver is a **build-time** choice baked into the klib.
2. Consumers include the framework as a composite build — `NewGate/netonapi-backend/settings.gradle.kts:12`
   `includeBuild("../../Neton/neton")`; `privchat/privchat-application/settings.gradle.kts` does the
   same. An included build has one build directory regardless of how many roots include it, so
   `Neton/neton/neton-database/build/` is **shared** by every consumer on the machine.
3. Up-to-date state is per *root* build (`netonapi-backend/.gradle/`, `privchat-application/.gradle/`).
   Neither root can see the other's history, and neither re-checks whether a foreign build overwrote
   the shared outputs.
4. So after consumer B compiles the sqlite variant into that shared directory, consumer A's next
   `linkDebugExecutable*` either (a) relinks against B's klib, or (b) declares itself UP-TO-DATE and
   keeps a kexe that was already linked against it. Both were observed, an hour apart.

`privchat-application/settings.gradle.kts:44-65` (`TEST-DRIVER-DEFAULT`) flips the default to
`sqlite` for test-only invocations, because its DB-backed tests run against `sqlite::memory:`. Its
comment states the guard is safe — "只要任务列表里混有任何非测试任务（如 linkDebugExecutable*），保持
postgres 不动，生产 binary 永远不会被这段逻辑改变". That reasoning is sound **within its own build**
and stops there: the test-only invocations still rewrite the shared framework klib, which is what
another consumer's production binary links. The observed invocation did not even need the injection —
it passed the property explicitly:

```
cd privchat/privchat-application && ./gradlew :module-game:macosArm64Test \
  --tests "RakedSettlementE2ETest*" -Pneton.database.driver=sqlite --no-configuration-cache
```

## Evidence (2026-09-08, one machine, two agent sessions running concurrently)

| time | what | variant |
|---|---|---|
| 04:58:41 | sibling's sqlite test build rewrites the shared klib | `sqlx4k-sqlite` |
| 04:59:20 | newgate's `linkDebugExecutableMacosArm64` runs (Main.kt had changed, so it really ran) and links that klib | kexe = sqlite |
| 05:02:44 | sibling's test kexe + test XML → a second sqlite build overlapping mine | — |
| 05:04:08 | my `--rerun-tasks -Pneton.database.driver=postgres` finishes; the klib on disk is sqlite again and the kexe (05:05:10) fails the scratch-DB probe | kexe = sqlite |
| 05:06:08 | control: `./gradlew :neton:neton-database:compileKotlinMacosArm64 --rerun -Pneton.database.driver=postgres`, run from `newgate`, nothing else building | `sqlx4k-postgres` |
| 05:07:25 | sibling writes the klib again | `sqlx4k-sqlite` |
| 05:08 | harness link step declares UP-TO-DATE (kexe mtime unchanged) while the klib is sqlite → shape (b) | kexe = sqlite |
| 05:09-05:15 | harness run: `migrate up` mismatches → heal branch → `--rerun-tasks` rebuild → migration OK → **108 passed / 0 failed over 56 scenarios** | kexe = postgres |
| 05:11:47 vs 05:12:05 | inside that heal, the sibling rewrote the shared klib to sqlite 18 s before the kexe was written; the binary still came out postgres (probed afterwards: `migrate up` applied 12 migrations, exit 0), so the link had already taken its inputs | klib = sqlite, kexe = postgres |

The 05:06:08 control is what rules out the boring explanations: the property is honoured, nothing in
`netonapi-backend/gradle.properties`, `Neton/neton/gradle.properties` or `~/.gradle/gradle.properties` sets
it, and no init script exists. The 05:04:08 row is what bounds the fix: `--rerun-tasks` inside one
invocation is still a race, because the framework compile and the app link are ~1 minute apart.

Reading the variant without running anything:

```
grep -o 'sqlx4k-[a-z]*' Neton/neton/neton-database/build/classes/kotlin/<target>/main/klib/neton-database/default/manifest
```

The kexe is **not** evidence — its strings are not plaintext, so `strings | grep` finds nothing
either way.

## It goes both ways

A `-Ppostgres` build from this repo rewrites the same klib, so the sibling's next test run can link
a postgres-variant `neton-database` and fail with `当前 build variant = postgres, 但 config.driver =
SQLITE`. Neither build is wrong in isolation; the shared directory is the defect. Whoever fixes this
should fix it for both sides at once.

## What the harness does now

- passes `-Pneton.database.driver=postgres` on the link step instead of eating the ambient default,
  so an unrelated `gradle.properties` cannot silently choose the variant;
- when `migrate up` reports `NETON-DB-VARIANT`, rebuilds with `--rerun-tasks` (which forces the
  framework module to recompile inside the same invocation as the link) and retries the migration
  once;
- if it still mismatches, says plainly that another build is writing the shared klib concurrently and
  that the only fix is to wait.

What has actually been exercised: the heal branch fired and recovered on the 05:09-05:15 run
(mismatch → `--rerun-tasks` → migration OK → 108/0), so the recovery path is not theoretical. The
*still-mismatched-after-healing* arm has **not** been reached end to end — that needs losing the race
twice in a row, and the 05:11:47 row shows how narrow the margin is: 18 seconds either way and the
heal would have produced another sqlite binary. Not foolproof, by construction.

## Plan (framework side)

**Recommended: put the variant in the output path.** In `Neton/neton`'s root build script, set
`layout.buildDirectory` per project from the resolved driver — e.g. `build-postgres/`,
`build-sqlite/` — or do it for `neton-database` alone, which is the only module whose compiled
output depends on the property. Two consumers then write to different directories and cannot see
each other, and a stale klib becomes impossible rather than merely detected.

Costs to plan for: the first build after the change is a full rebuild; disk use roughly doubles per
variant present on the machine; anything that hardcodes `neton-database/build/` (CI cache keys,
artifact upload paths, `scripts/embed-migration-resources.gradle.kts`, module build scripts that read
framework outputs by path) has to carry the variant too.

### Rejected alternatives

- **Make sqlite a separate K/N target or source set** rather than a property that rewrites shared
  `srcDirs`. Cleaner conceptually, but a much larger change, and the one-driver-per-executable
  constraint that motivated the variant stays exactly where it is.
- **A second framework checkout (or `git worktree`) per consumer.** No code change, and it works —
  until someone points two consumers at one clone again, at which point the hazard returns with no
  build-script change to show for it. Nothing in the build would flag that.
- **A cross-build lock file serializing gradle invocations.** Turns a silently wrong binary into a
  wait, but only if every consumer opts in, which is the same coordination problem as the checkout
  split, with more moving parts.
- **Do nothing beyond the harness heal.** Defensible while exactly one machine is affected and the
  harness is the only thing that links an app binary day to day. It is what is in place today.

## Verification criteria

- Script a loop that alternates `newgate`'s link task and the sibling's sqlite test build, N times,
  with no waiting in between. After each step, the klib manifest read from **each consumer's own**
  output path must name the driver that consumer asked for, and `"$APP" migrate up` against a scratch
  postgres database must succeed every time.
- The harness's heal branch must **not** fire anywhere in that loop. It firing is the regression
  signal, not the pass signal — a run that heals is a run that was broken first.
- Delete `Neton/neton/neton-database/build*` and build both consumers cold, in either order, with no
  `--rerun-tasks`: both must produce binaries that match their own `database.conf`.

## Why P2 and not P1

No release or CI exposure: a runner that checks out one consumer has no sibling to race with, and
`DEPLOY.md` states PostgreSQL as the sole supported dialect for this distribution, so shipped
binaries are built postgres-only from a clean tree. What it costs today is developer time on one
machine, and the harness already converts the worst case into one extra rebuild.

It becomes P1 the day a release binary is built on a machine that also builds another consumer with
a different variant — then the artifact dies on its first database connect in front of a customer,
and the build log says nothing at all was wrong.
