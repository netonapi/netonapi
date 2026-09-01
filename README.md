# Neton Application

An enterprise-grade admin system built on the [Neton](../neton) framework, compiled to native binaries via Kotlin/Native — fast startup, low memory footprint, single-file deployment.

> Engineering rules: see [ENGINEERING_RULES.md](./ENGINEERING_RULES.md)
>
> Module template spec: see [docs/module-template-spec.md](./docs/module-template-spec.md)

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin 2.3 (Multiplatform) |
| Framework | Neton 1.0.0-beta1 |
| Compilation | Kotlin/Native → Native Binary |
| Database | MySQL / PostgreSQL / SQLite |
| Authentication | JWT |
| Build | Gradle 8.14 + KSP |
| Logging | Structured JSON, async writes |

**Runtime Metrics:**

- Cold start ~150ms (debug) / ~3ms (release)
- Memory usage ~20MB
- Single-file deployment, no JVM dependency

## Repository Structure

Uses a **multi-repo + Gradle Composite Build** architecture with the main repository and extension modules separated:

| Repository | Contents | Inclusion Method |
|------------|----------|-----------------|
| `neton` | Framework | `includeBuild("../neton")` |
| `neton-application` | Main app (module-system + module-infra + application entry) | Root project |
| `neton-application-module-member` | Member module | `includeBuild` |
| `neton-application-module-payment` | Payment module | `includeBuild` |
| `neton-application-module-platform` | Open platform module | `includeBuild` |
| `neton-application-admin` | Admin frontend (Vue3 + Ant Design Vue) | Standalone repo |

Directory layout:

```
projects/
├── neton/                              # Framework
├── neton-application/                  # Main repo (this project)
│   ├── application/                    # Application entry
│   │   ├── src/commonMain/kotlin/
│   │   │   ├── Main.kt                # Startup entry
│   │   │   ├── config/                # SecurityConfig, etc.
│   │   │   └── controller/            # HomeController (health check)
│   │   └── config/                    # TOML config files
│   │       ├── application.conf       # Server & logging config
│   │       ├── database.conf          # Database connection
│   │       └── routing.conf           # Route group definitions
│   ├── module-system/                  # System management (core)
│   ├── module-infra/                   # Infrastructure
│   ├── sql/                            # Main DB migration scripts
│   │   ├── mysql/
│   │   ├── postgresql/
│   │   └── sqlite/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
├── neton-application-module-member/    # Member module (standalone repo)
├── neton-application-module-payment/   # Payment module (standalone repo)
├── neton-application-module-platform/  # Open platform module (standalone repo)
└── neton-application-admin/            # Admin frontend
```

## Architecture

### Layered Architecture

```
Controller  ──  HTTP routing, parameter binding, permission annotations
     │
   Logic    ──  Business logic, state machines, transaction orchestration
     │
   Table    ──  Data access (KSP auto-generated CRUD)
     │
   DB       ──  MySQL / PostgreSQL / SQLite
```

### Route Groups

| Group | Mount Path | Purpose | requireAuth |
|-------|-----------|---------|-------------|
| `admin` | `/admin` | Admin panel API | true |
| `app` | `/app` | Consumer / mobile API | true |
| `open` | `/platform` | Open platform API | As needed |

### Module Dependencies

```
application
├── module-system        ← Core, depended upon by all modules
├── module-infra         ← Depends on system
├── module-member        ← Depends on system (standalone repo)
├── module-payment       ← Depends on system (standalone repo)
└── module-platform      ← Depends on system (standalone repo)
```

## Feature Modules

### module-system — System Management

RBAC permission system, authentication, organizational structure.

| Feature | Description |
|---------|-------------|
| Authentication | Username/password login, SMS login, social login (Google / Telegram), token refresh |
| User Management | CRUD, enable/disable, password reset, user profile |
| Role Management | CRUD, role assignment, menu permission assignment |
| Menu Management | Tree-structured CRUD |
| Permission Management | Fine-grained permission query and assignment |
| Department Management | Tree-structured org hierarchy CRUD |
| Post Management | Post CRUD and listing |
| Dictionary Management | Dictionary type + dictionary data CRUD |
| Logging | Login logs, operation audit logs |
| Notices | System announcement CRUD |
| Messaging | Message channels, message templates, send records, SMS/email/notification templates |
| Social Users | Third-party social account binding management |

### module-infra — Infrastructure

| Feature | Description |
|---------|-------------|
| Dynamic Config | Key-value configuration management |
| File Management | File upload, S3 pre-signed URLs, multi-storage backends |
| Storage Config | Storage backend CRUD, connectivity testing, default storage switching |
| Scheduled Jobs | Job CRUD, execution logs, cron scheduling |
| API Logs | Access logs + error logs, request/response tracing |
| Redis Monitoring | Redis health check and statistics |

### module-member — Membership System

| Feature | Description |
|---------|-------------|
| Member Auth | Consumer registration/login/logout, SMS verification, social registration |
| Member Management | Member CRUD, groups, tags |
| Level System | Level definition, experience points, auto-upgrade |
| Points System | Points issuance, points history, redemption |
| Check-in System | Daily check-in rewards, check-in records, streak tracking |
| Shipping Addresses | Address CRUD |

### module-payment — Payment System

| Feature | Description |
|---------|-------------|
| Payment Apps | Multi-app management, key generation |
| Payment Channels | Multi-gateway integration, channel configuration |
| Payment Orders | Order creation, state machine transitions, order query |
| Refunds | Refund application and processing |
| Wallet | Account wallet, balance transactions |
| Top-up | Top-up packages, top-up records |
| Transfers | Account-to-account transfers |
| Async Notifications | Payment callback query (webhook endpoint TBD) |

### module-platform — Open Platform

| Feature | Description |
|---------|-------------|
| Client Management | Third-party client registration, AppID/Secret generation |
| API Catalog | API definitions, authorization policies, pricing configuration |
| Signature Verification | HMAC request signing and verification |
| Billing & Statistics | API call billing, usage statistics, billing records |

## Quick Start

### Prerequisites

- **JDK 17+** (required for Gradle build, not needed at runtime)
- **Git**
- **MySQL** (or PostgreSQL / SQLite)

### 1. Clone the Project

```bash
git clone <repository-url>
cd neton-application
```

Ensure the Neton framework and extension modules are in sibling directories:

```
projects/
├── neton/                              # Framework
├── neton-application/                  # This project
├── neton-application-module-member/    # Member module
├── neton-application-module-payment/   # Payment module
└── neton-application-module-platform/  # Open platform module
```

### 2. Initialize the Database

Each repository contains its own `sql/` directory. Execute them sequentially against the **same database**.

#### MySQL

```bash
# Main DB (system + infra)
mysql -u root -p neton-application < sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < sql/mysql/V002__init_data.sql

# Member module
mysql -u root -p neton-application < ../neton-application-module-member/sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < ../neton-application-module-member/sql/mysql/V002__init_data.sql

# Payment module
mysql -u root -p neton-application < ../neton-application-module-payment/sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < ../neton-application-module-payment/sql/mysql/V002__init_data.sql

# Open platform module
mysql -u root -p neton-application < ../neton-application-module-platform/sql/mysql/V001__create_tables.sql
mysql -u root -p neton-application < ../neton-application-module-platform/sql/mysql/V002__init_data.sql
```

#### PostgreSQL

```bash
# Main DB (system + infra)
psql -U postgres -d neton-application -f sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f sql/postgresql/V002__init_data.sql

# Member module
psql -U postgres -d neton-application -f ../neton-application-module-member/sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f ../neton-application-module-member/sql/postgresql/V002__init_data.sql

# Payment module
psql -U postgres -d neton-application -f ../neton-application-module-payment/sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f ../neton-application-module-payment/sql/postgresql/V002__init_data.sql

# Open platform module
psql -U postgres -d neton-application -f ../neton-application-module-platform/sql/postgresql/V001__create_tables.sql
psql -U postgres -d neton-application -f ../neton-application-module-platform/sql/postgresql/V002__init_data.sql
```

#### SQLite

```bash
mkdir -p application/data application/logs

# Main DB (system + infra)
sqlite3 application/data/backend.db < sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < sql/sqlite/V002__init_data.sql

# Member module
sqlite3 application/data/backend.db < ../neton-application-module-member/sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < ../neton-application-module-member/sql/sqlite/V002__init_data.sql

# Payment module
sqlite3 application/data/backend.db < ../neton-application-module-payment/sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < ../neton-application-module-payment/sql/sqlite/V002__init_data.sql

# Open platform module
sqlite3 application/data/backend.db < ../neton-application-module-platform/sql/sqlite/V001__create_tables.sql
sqlite3 application/data/backend.db < ../neton-application-module-platform/sql/sqlite/V002__init_data.sql
```

### 3. Configure Database Connection

Edit `application/config/database.conf` for your database:

```toml
# MySQL
[default]
driver = "MYSQL"
uri = "mysql://root:123456@localhost:3306/neton-application"

# PostgreSQL
# [default]
# driver = "POSTGRESQL"
# uri = "postgresql://postgres:123456@localhost:5432/neton-application"

# SQLite
# [default]
# driver = "SQLITE"
# uri = "sqlite://data/backend.db"
```

### 4. Build

```bash
# macOS ARM64 (Apple Silicon)
./gradlew :application:linkDebugExecutableMacosArm64

# macOS x64
./gradlew :application:linkDebugExecutableMacosX64

# Linux x64
./gradlew :application:linkDebugExecutableLinuxX64

# Linux ARM64
./gradlew :application:linkDebugExecutableLinuxArm64
```

> The first build downloads the Kotlin/Native toolchain, which takes longer. Subsequent incremental builds are faster.

### 5. Run

**Must be launched from the `application` directory** to correctly load configuration:

```bash
cd application
./build/bin/macosArm64/debugExecutable/application.kexe
```

Output:

```
░█▀█░█▀▀░▀█▀░█▀█░█▀█
░█░█░█▀▀░░█░░█░█░█░█
░▀░▀░▀▀▀░░▀░░▀▀▀░▀░▀░

Neton 1.0.0-beta1
Kotlin/Native Runtime

Adapter     : Ktor CIO
Environment : dev
Port        : 7080
Cold Start  : 154 ms

Ready → http://localhost:7080
```

### 6. Verify

```bash
curl http://localhost:7080/
# {"status":"ok","service":"neton-application","version":"1.0.0"}
```

## Configuration

### application.conf

```toml
[application]
name = "neton-application"
debug = false

[server]
port = 7080
host = "0.0.0.0"

[database]
driver = "MYSQL"
uri = "mysql://root:123456@localhost:3306/neton-application"

[logging]
level = "INFO"

[logging.async]
enabled = true
queueSize = 8192
flushEveryMs = 200
flushBatchSize = 64
shutdownFlushTimeoutMs = 2000

[[logging.sinks]]
name = "access"
file = "logs/access.log"
levels = "INFO"
route = "http.access"

[[logging.sinks]]
name = "error"
file = "logs/error.log"
levels = "ERROR,WARN"

[[logging.sinks]]
name = "all"
file = "logs/all.log"
levels = "ALL"
```

### database.conf

```toml
[default]
driver = "MYSQL"
uri = "mysql://root:123456@localhost:3306/neton-application"
debug = false
```

### routing.conf

```toml
[[groups]]
group = "admin"
mount = "/admin"
requireAuth = true

[[groups]]
group = "app"
mount = "/app"
requireAuth = true

[[groups]]
group = "open"
mount = "/platform"
```

### Environment Overrides

Supports loading environment-specific configuration:

```bash
# Development (default)
./application.kexe

# Production → loads application.prod.conf as override
./application.kexe --env=prod
```

Configuration priority: CLI args > Environment variables > Environment config file > Base config file > Framework defaults

## Build Targets

| Platform | Target | Output Path |
|----------|--------|-------------|
| macOS ARM64 | `macosArm64` | `application/build/bin/macosArm64/debugExecutable/` |
| macOS x64 | `macosX64` | `application/build/bin/macosX64/debugExecutable/` |
| Linux x64 | `linuxX64` | `application/build/bin/linuxX64/debugExecutable/` |
| Linux ARM64 | `linuxArm64` | `application/build/bin/linuxArm64/debugExecutable/` |
| Windows x64 | `mingwX64` | `application/build/bin/mingwX64/debugExecutable/` |

For release builds: replace `linkDebugExecutable` with `linkReleaseExecutable`.

## Container Deployment

```dockerfile
FROM scratch
COPY build/bin/linuxX64/releaseExecutable/application.kexe /app
COPY application/config/ /config/
EXPOSE 7080
ENTRYPOINT ["/app", "--env=prod"]
```

```bash
# Build image (~10MB)
docker build -t neton-application .
docker run -p 7080:7080 neton-application
```

## Development Guide

### Adding a New Controller

```kotlin
// module-system/src/commonMain/kotlin/controller/admin/example/ExampleController.kt
package controller.admin.example

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Permission

@Controller("/example")
class ExampleController(private val logic: ExampleLogic) {

    @Get("/list")
    @Permission("system:example:list")
    suspend fun list(page: Int = 1, size: Int = 20) = logic.page(page, size)

    @Post("/create")
    @Permission("system:example:create")
    suspend fun create(request: CreateExampleRequest): Long = logic.create(request)
}
```

KSP auto-generates route registration code — no manual route configuration needed.

### Adding a New Model + Table

```kotlin
// model/Example.kt
@Serializable
@Table("system_examples")
data class Example(
    @Id val id: Long = 0,
    val name: String,
    val status: Int = 0,
    @SoftDelete val deleted: Int = 0,
    @CreatedAt val createdAt: String? = null,
    @UpdatedAt val updatedAt: String? = null
)

// table/ExampleTable.kt (Facade, add custom methods here)
object ExampleTable : Table<Example, Long> by ExampleTableImpl
```

KSP auto-generates `ExampleTableImpl`, `ExampleMeta`, `ExampleRowMapper`, and 3 other files.

### Build Commands

```bash
# Compile only (no linking)
./gradlew :application:compileKotlinMacosArm64

# Compile + link
./gradlew :application:linkDebugExecutableMacosArm64

# Clean rebuild
./gradlew clean :application:linkDebugExecutableMacosArm64

# Run KSP code generation only
./gradlew :module-system:kspKotlinMacosArm64
```

## Related Documentation

| Document | Description |
|----------|-------------|
| **SPEC.md** | Project architecture, API specifications, configuration specs |

## License

Private - All Rights Reserved
