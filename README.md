# neton-application-module-template

Minimal Neton module template.

This project intentionally keeps only a `helloworld` example so it can be copied as:

- an internal module inside `neton-application`
- an external standalone module repository

It is not wired into composite build by default.
Decide that only after the copied module has a real business boundary.

Before expanding it, read the spec in:

- `/Users/zoujiaqing/projects/Neton/neton-application/docs/module-template-spec.md`

## What stays in this template

- minimal Gradle/KMP project structure
- module initializer sample
- hello world controller sample
- one tiny test to keep the module shape stable

## Current shape

- `controller/admin/helloworld/HelloWorldController.kt`
- `init/HelloWorldModuleInitializer.kt`

This mirrors the real module layout without forcing the full Neton runtime
dependency into the template baseline.

## What does not stay here

- CRUD tables
- config samples
- log samples
- generated SQL
- frontend pages

Those belong in real modules, guided by the spec, not hardcoded into the template repository.
