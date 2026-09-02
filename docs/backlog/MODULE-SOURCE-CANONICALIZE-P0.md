# MODULE-SOURCE-CANONICALIZE-P0

## Status

**CLOSED.**

## Standard

Neton application modules support two build modes:

- standalone mode uses the module repository's own `settings.gradle.kts`
- aggregate mode registers sibling repositories as source subprojects with `include(...)` and `projectDir`

The aggregate root maps stable module coordinates to source projects through Gradle dependency substitution. External modules must not include the aggregate application because that creates a cyclic composite. Aggregate builds must not depend on `publishToMavenLocal` or a specifically named parent directory.

Example:

```kotlin
include(":module-order")
project(":module-order").projectDir = file("../neton-application-module-order")
```

## Verification

- `neton-application :application:compileKotlinMacosArm64` passes
- `newgate :application:compileKotlinMacosArm64` passes
- canonical module repositories remain independently buildable
- product assembly no longer relies on cyclic `includeBuild` wiring
- no local Maven publication is required
