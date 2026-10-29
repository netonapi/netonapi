# NetonAPI Backend

**English** | [简体中文](./README.zh-Hans.md)

NetonAPI is a self-hosted LLM API gateway. One set of issued keys serves three upstream
protocol families — OpenAI, Anthropic, Gemini — relayed in any direction, with durable
billing, quota enforcement and channel failover. It is built with Kotlin/Native on the
[Neton](https://github.com/netonframework/neton) framework and the [Neton Application](https://github.com/neton-application/neton-application) scaffold, and compiles to a single static executable: no
JVM at runtime, no sidecar SQL files. See the [capability comparison](#capability-comparison)
with [`new-api`](https://github.com/QuantumNous/new-api).

This repository is the **backend distribution**. It assembles the framework and the seven
canonical modules into one binary and owns the gateway-specific configuration. Product
logic lives in the module repositories, not here.

| | |
|---|---|
| Language | Kotlin 2.4.20 (Multiplatform) |
| Build | Gradle 8.14.4 + KSP 2.3.12 |
| Runtime | Kotlin/Native static executable (`application.kexe`), no JVM |
| Datastores | PostgreSQL (billing, ledger, channels) · Redis (optional: shared rate limits) |
| Default port | `8800` |
| License | [Apache-2.0](./LICENSE) |

Source repositories: [**netonapi/netonapi**](https://github.com/netonapi/netonapi) (this server),
[**netonapi/netonapi-admincp**](https://github.com/netonapi/netonapi-admincp) (operator console) and
[**netonapi/netonapi-web**](https://github.com/netonapi/netonapi-web) (public site and self-service console). Deployment, secrets, reverse-proxy and SSRF notes:
[`DEPLOY.md`](https://github.com/zoujiaqing/nanogate/blob/main/DEPLOY.md) in the `nanogate`
repository, which also carries the end-to-end test harness.

## Capability comparison

| Capability | NetonAPI | [new-api](https://github.com/QuantumNous/new-api) |
|---|---|---|
| Implementation & deployment | Single static Kotlin/Native executable, no JVM at runtime | Go single binary / Docker |
| Database | PostgreSQL (Redis optional) | SQLite / MySQL / PostgreSQL (Redis optional) |
| Inbound protocols | Native OpenAI, Anthropic and Gemini formats, converted in any direction (tool calls, images, thinking and cache usage preserved across protocols) | OpenAI format first, with Claude and Gemini formats supported |
| Upstream types | OpenAI-compatible, Azure OpenAI, Anthropic, Gemini, AWS Bedrock, Vertex AI | Dozens of channel types, including task-style upstreams such as Midjourney and Suno |
| Channel scheduling | Priority + weight, failover, automatic key disabling and cool-down, latency and connectivity tests | Priority + weight, retries, automatic disabling |
| Sale price | Anchored on the official price: platform price × group ratio; per-model agreed prices for groups or users (a discount off the official price, or a fixed price); tiered pricing | Model ratio × completion ratio × group ratio, or a fixed per-call price |
| Channel cost & margin | Real cost per channel (official-price discount / pulled from the upstream price list / entered per model); prices below cost are rejected; pricing check and margin reports | No channel cost accounting |
| Official prices | Synced from a price source on a schedule; abnormal changes held for review; price history kept | Ratios maintained by hand, upstream ratio sync available |
| Ledger | Two-phase reserve → settle, recoverable after a crash, anomalies go to a manual review queue | Quota pre-deducted, adjusted after the request |
| Billing group | Decided by the API key (chosen by the user from allowed groups; operators can assign or revoke) | User group, with per-token group selection |
| API keys | Shown once at creation; no plaintext stored | Viewable and copyable from the list at any time |
| Top-ups | Manual top-up by operators, redemption codes, online checkout (Alipay, aggregator gateways, pluggable); payment channel secrets are write-only | Redemption codes, EPay, Stripe |
| User sign-in | Username and password, email password reset | Username and password, plus GitHub, OIDC, Telegram, WeChat and other third-party sign-in, and 2FA |
| Task-style APIs | Not supported (Midjourney, Suno, video generation) | Supported |
| Operator permissions | Menu- and button-level permissions; preset gateway operator / finance / support / read-only roles | Admin / root |

The new-api column is compiled from its public documentation; check the latest new-api release for details.

## Repository layout

This repo is **not self-contained**. `settings.gradle.kts` hardcodes `../../Neton/<repo>`,
so it must sit exactly two levels below the directory that holds `Neton/`:

```
projects/
├── Neton/
│   ├── neton/                              # framework (includeBuild)
│   ├── neton-ai/                           # LLM protocol codecs (includeBuild)
│   ├── geolite4k/                          # GeoIP (includeBuild)
│   ├── neton-application-contract/         # module contracts (includeBuild)
│   ├── neton-application-module-system/
│   ├── neton-application-module-infra/
│   ├── neton-application-module-account/
│   ├── neton-application-module-member/
│   ├── neton-application-module-payment/
│   └── neton-application-module-platform/
└── <workspace>/                            # any name for Gradle…
    └── netonapi-backend/                   # ← this repository
        └── module-gateway/                 # the NetonAPI gateway module (lives here, not in Neton/)
```

The framework and the modules are pulled in as **source subprojects** (`include(...)` +
`projectDir`), not as `includeBuild` — each of them keeps its own `settings.gradle.kts` and
stays independently buildable. Only `neton`, `neton-ai`, `geolite4k` and
`neton-application-contract` are composite builds.

### Build prerequisites

Building needs read access to every repository listed above. `neton-application-module-infra`
is currently private, so a build from public sources alone is not possible until it is
published. The Docker build also expects the outer
directory names `Neton/` and `NewGate/`, because its `COPY` sources are literal paths.

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
(`NETON_REDIS__HOST` → `redis.host`), plus `NETONAPI_*` for product knobs such as
`NETONAPI_CHANNEL_KEY_ENC_KEY`, `NETONAPI_SALE_MARKUP` and `NETONAPI_MIN_MARGIN`. Behind a
reverse proxy set `NETON_ROUTING__RATELIMIT__TRUSTED_PROXIES` (e.g. `127.0.0.1,::1`) so rate
limits, audit and token IP allowlists see the real client. Only PostgreSQL is supported.

Route groups (see `application/config/routing.conf`):

| Mount | Auth | Purpose |
|---|---|---|
| `/` | gateway `sk-` token | LLM relay: `/v1/chat/completions`, `/v1/messages`, `/v1/embeddings`, Gemini `:generateContent` / `:embedContent` |
| `/admin` | operator JWT | operator console API |
| `/app` | member JWT (HttpOnly cookie) | self-service console API |
| `/platform` | per endpoint | open-platform endpoints |

## Assembled modules

The module list has exactly one source of truth — `ksp { arg("neton.modules", ...) }` in
`application/build.gradle.kts`. KSP generates `GeneratedApplicationModules`, which the
`migrate` subcommand, the startup precheck and `serve` all consume, so adding or dropping a
module is a two-line change (a dependency plus an id) and cannot drift out of sync.

`system` · `infra` · `account` · `member` · `payment` · `platform` · `gateway`

## Tests

The behavioural test suite is the harness in the
[`nanogate` repository](https://github.com/zoujiaqing/nanogate) (`harness/run.sh`): it starts
the real gateway against a throwaway PostgreSQL database and an isolated `redis-server`, and
drives it scenario by scenario with Python fake upstreams — billing and settlement, pricing
rules and margin protection, protocol conversion, channel failover and cool-down, rate
limits, payments and top-ups, permissions, and the SSRF and XFF trust boundaries. It never
touches a Redis instance it did not start. `harness/live-e2e.py` runs the same kind of drill
against a deployed instance (see `docs/live-drill-spec.md`).

```bash
cd ..          # the workspace root that holds harness/
bash harness/run.sh                          # macOS: builds macosArm64 by default
NETONAPI_TARGET=linuxX64 bash harness/run.sh  # Linux
```

## Documentation

- [`docs/SPEC.md`](./docs/SPEC.md) — overall backend spec: repositories and assembly, layering,
  route groups, health checks, configuration, response envelope, pagination, migrations
- [`docs/ENGINEERING_RULES.md`](./docs/ENGINEERING_RULES.md) — the hard rules (module
  boundaries, what to grep before changing a public symbol, migration policy)
- [`docs/gateway-pricing-spec.md`](./docs/gateway-pricing-spec.md) — pricing rules: sale price modes, billing groups,
  margin protection, pricing check, official price calibration and price history
- [`docs/model-catalog-design.md`](./docs/model-catalog-design.md) — the offered model catalog: listed vs. price library, serving upstreams
- [`docs/key-billing-group-design.md`](./docs/key-billing-group-design.md) — billing groups come from the API key
- [`docs/api-key-spec.md`](./docs/api-key-spec.md) — API keys: user-created only, shown once, ops supervision and limits
- [`docs/recharge-spec.md`](./docs/recharge-spec.md) — credit and top-ups: manual top-up, online checkout, sandbox, payment sync; no mock top-up
- [`docs/console-and-site-spec.md`](./docs/console-and-site-spec.md) — public site and user console
- [`docs/live-drill-spec.md`](./docs/live-drill-spec.md) — live drill and mock upstreams
- [`docs/relay-errors-spec.md`](./docs/relay-errors-spec.md) — relay error handling: fail over on upstream account errors, no leaking internals, cross-protocol paths
- [`docs/rbac-spec.md`](./docs/rbac-spec.md) — User/Role/Menu, permission strings, wildcards
- [`docs/module-template-spec.md`](./docs/module-template-spec.md) — how to add a module

## Known gaps

Deliberately listed rather than hidden:

- CORS `Access-Control-Allow-Origin` is **hardcoded to `*`** in `Main.kt` and is not a config
  value — Neton's TOML parser cannot parse arrays. `allowCredentials = false`, so browsers
  send no cookies cross-origin; the admin UI therefore shares the API origin through Caddy
  (production) or Next dev rewrites (local). Tightening the allowlist means editing code.
- `security.unified_token.enabled` must stay `false`: no module in this distribution binds a
  `UnifiedTokenAuthenticator`. The startup error says so explicitly.
- The Docker/compose build path has not been executed on a real Docker host yet. Health
  checks: `GET /health` (liveness + database, used by the image's `HEALTHCHECK`) and
  `GET /admin/system/health/ready` (database and Redis).
- SMTP with TLS relies on the system libcurl (present on macOS and in the Debian image);
  the Windows build only supports plaintext SMTP and should use a vendor HTTP mail API.

## License

Apache-2.0 — see [LICENSE](./LICENSE).
