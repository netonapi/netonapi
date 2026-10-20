# NetonAPI Backend

**English** | [简体中文](./README.zh-Hans.md)

NetonAPI is a self-hosted LLM API gateway. One set of issued keys serves three upstream
protocol families — OpenAI, Anthropic, Gemini — relayed in any direction, with durable
billing, quota enforcement and channel failover. It is a rewrite of the Go-based
[`new-api`](https://github.com/QuantumNous/new-api) on the
[Neton](https://github.com/netonframework/neton) framework, compiled with Kotlin/Native to
a single static executable: no JVM at runtime, no sidecar SQL files.

This repository is the **backend distribution**. It assembles the framework and the six
canonical modules into one binary and owns the gateway-specific configuration. Product
logic lives in the module repositories, not here.

| | |
|---|---|
| Language | Kotlin 2.4.20 (Multiplatform) |
| Build | Gradle 8.14.4 + KSP 2.3.10 |
| Runtime | Kotlin/Native static executable (`application.kexe`), no JVM |
| Datastores | PostgreSQL (billing, ledger, channels) · Redis (optional: shared rate limits) |
| Default port | `8800` |
| License | [Apache-2.0](./LICENSE) |

Companion repositories: [**netonapi-frontend**](https://github.com/netonapi/netonapi-frontend) (operator console) and
[**netonapi-client**](https://github.com/netonapi/netonapi-client) (self-service console). Deployment, secrets, reverse-proxy and SSRF notes:
[`DEPLOY.md`](https://github.com/zoujiaqing/nanogate/blob/main/DEPLOY.md) in the `nanogate`
repository, which also carries the end-to-end test harness.

## Repository layout

This repo is **not self-contained**. `settings.gradle.kts` hardcodes `../../Neton/<repo>`,
so it must sit exactly two levels below the directory that holds `Neton/`:

```
projects/
├── Neton/
│   ├── neton/                              # framework (includeBuild)
│   ├── geolite4k/                          # GeoIP (includeBuild)
│   ├── neton-application-module-system/
│   ├── neton-application-module-infra/
│   ├── neton-application-module-account/
│   ├── neton-application-module-member/
│   ├── neton-application-module-payment/
│   ├── neton-application-module-platform/
│   └── neton-application-module-gateway/   # the NetonAPI core
└── <workspace>/                            # any name for Gradle…
    └── netonapi-backend/                   # ← this repository
```

The framework and the modules are pulled in as **source subprojects** (`include(...)` +
`projectDir`), not as `includeBuild` — each of them keeps its own `settings.gradle.kts` and
stays independently buildable. Only `neton` and `geolite4k` are composite builds.

> ⚠️ **You cannot build this repo from a clean clone yet.** `neton-application-module-infra`
> and `neton-application-module-gateway` — the module that actually implements the gateway —
> are private at the moment. Until they are public (or the modules ship as Maven artifacts),
> publishing this repo means "the source is readable", not "anyone can build it".
> The Docker build additionally pins the outer directory names (`Neton/`, `NewGate/`) because
> its `COPY` sources are literal.

## Build

```bash
./gradlew :application:linkDebugExecutableMacosArm64      # Apple silicon dev build
./gradlew :application:linkReleaseExecutableLinuxX64      # the release artifact the image ships
./gradlew :module-gateway:macosArm64Test :module-system:macosArm64Test
```

Output: `application/build/bin/<target>/<debug|release>Executable/application.kexe`.
Kotlin/Native test tasks only run on a matching host architecture — a `linuxX64Test` task
cannot execute on an Apple silicon Mac — so CI splits the work: the macOS job compiles
`macosArm64` and runs the module unit tests, the Linux job builds `linuxX64` natively and
runs the reliability harness against that binary.

Declared targets: `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64`. Verified in practice:

| Target | Status |
|---|---|
| `macosArm64` | compiles, links, unit tests run (dev machine + the CI macOS job) |
| `linuxX64` | compiles, links and passes the harness on the CI Linux job; also cross-linked from macOS to a working ELF as a local check |
| `linuxArm64` | declared, **not verified** |
| `mingwX64` | declared, **not verified**; ships without a DNS resolver, so the SSRF re-check degrades to literal-only validation (see `DEPLOY.md`) |

## Run

```bash
./application.kexe migrate up     # apply migrations; also accepts `status` / `verify`
./application.kexe                # serve on :8800
```

Serving **never auto-migrates**: startup runs a precheck and aborts (exit 1) if migrations
are pending, failed, or mismatched. Migrations are embedded into the binary at compile time,
so there are no SQL files to ship and no working-directory assumptions.

Configuration lives in `application/config/*.conf` (`application.conf`, `database.conf`,
`routing.conf`, `storage.conf`, `geolite.conf`) and every value can be overridden by
environment variables — `NETON_` prefix, `__` for a dot, path lowercased
(`NETON_REDIS__HOST` → `redis.host`), plus `NEWGATE_*` for product knobs such as
`NEWGATE_CHANNEL_KEY_ENC_KEY`, `NEWGATE_SALE_MARKUP` and `NEWGATE_MIN_MARGIN`. Behind a
reverse proxy set `NETON_ROUTING__RATELIMIT__TRUSTED_PROXIES` (e.g. `127.0.0.1,::1`) so rate
limits, audit and token IP allowlists see the real client. Only PostgreSQL is supported.

Route groups (see `application/config/routing.conf`):

| Mount | Auth | Purpose |
|---|---|---|
| `/` | gateway `sk-` token | LLM relay: `/v1/chat/completions`, `/v1/messages`, `/v1/embeddings`, Gemini `:generateContent` / `:embedContent` |
| `/admin` | operator JWT | operator console API |
| `/app` | member JWT (HttpOnly cookie) | self-service console API |
| `/platform` | open | open-platform endpoints |

## Assembled modules

The module list has exactly one source of truth — `ksp { arg("neton.modules", ...) }` in
`application/build.gradle.kts`. KSP generates `GeneratedApplicationModules`, which the
`migrate` subcommand, the startup precheck and `serve` all consume, so adding or dropping a
module is a two-line change (a dependency plus an id) and cannot drift out of sync.

`system` · `infra` · `member` · `payment` · `platform` · `gateway`

## Tests

The behavioural test suite is the harness in the
[`nanogate` repository](https://github.com/zoujiaqing/nanogate) (`harness/run.sh`): it starts
the real gateway on `:8800` against a throwaway PostgreSQL database and an isolated
`redis-server`, drives 25 scenarios with Python fake upstreams, and asserts 37 properties —
billing snapshots, dual-track pricing, margin gates, capability routing, channel failover and
cool-down, atomic rate limits, and the XFF trust boundary. It never touches a Redis instance
it did not start.

```bash
cd ..          # the workspace root that holds harness/
bash harness/run.sh                          # macOS: builds macosArm64 by default
NEWGATE_TARGET=linuxX64 bash harness/run.sh  # Linux
```

## Documentation

- [`docs/ENGINEERING_RULES.md`](./docs/ENGINEERING_RULES.md) — the hard rules (module
  boundaries, what to grep before changing a public symbol, migration policy)
- [`docs/gateway-pricing-spec.md`](./docs/gateway-pricing-spec.md) — pricing rules: sale price modes, billing groups,
  margin protection, pricing check, official price calibration and price history
- [`docs/rbac-spec.md`](./docs/rbac-spec.md) — User/Role/Menu, permission strings, wildcards
- [`docs/module-template-spec.md`](./docs/module-template-spec.md) — how to add a module
- [`docs/SPEC.md`](./docs/SPEC.md) — ⚠️ inherited scaffold, **partly stale**: it still says
  Kotlin 2.3, describes the modules as `includeBuild`, links a file that is not in this repo,
  and names a Vue admin console that NetonAPI does not use. Trust this README and `DEPLOY.md`.

## Known gaps

Deliberately listed rather than hidden:

- CORS `Access-Control-Allow-Origin` is **hardcoded to `*`** in `Main.kt` and is not a config
  value — Neton's TOML parser cannot parse arrays. `allowCredentials = false`, so browsers
  send no cookies cross-origin; the admin UI therefore shares the API origin through Caddy
  (production) or Next dev rewrites (local). Tightening the allowlist means editing code.
- `security.unified_token.enabled` must stay `false`: no module in this distribution binds a
  `UnifiedTokenAuthenticator`. The startup error says so explicitly.
- The Docker/compose build path has not been executed on a real Docker host yet.
- SMTP with TLS relies on the system libcurl (present on macOS and in the Debian image);
  the Windows build only supports plaintext SMTP and should use a vendor HTTP mail API.

## License

Apache-2.0 — see [LICENSE](./LICENSE).
