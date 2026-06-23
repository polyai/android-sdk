# Contributing

Thanks for helping improve the **PolyMessaging Android SDK**. This SDK is held to
a high quality bar.

## Requirements

- **JDK 17** (the build pins a JDK 17 toolchain; AGP requires JDK 17 to run).
- **Android Studio** (latest stable) or the command-line Android SDK with API 36 installed.
- No global Gradle needed — use the wrapper (`./gradlew`).

## Build & verify

```bash
./gradlew :polymessaging:assembleRelease   # build the AAR
./gradlew build                            # compile + lint + unit tests + apiCheck
./gradlew test                             # JVM unit + resilience/stress tests
./gradlew lint                             # Android lint
./gradlew apiCheck                         # public-API surface guard (binary-compatibility-validator)
./scripts/verify.sh                        # local ship gate (unit tests + assemble all samples; real exit code)
```

If you change the **public API**, run `./gradlew apiDump` and commit the updated
`polymessaging/api/polymessaging.api`.

## Code style

- 4-space indent; types `UpperCamelCase`; members `lowerCamelCase`; `kotlin.code.style=official`.
- **Every new `.kt` file must start with `// Copyright PolyAI Limited`** followed by a blank line.
- Public types and members need a KDoc describing **why** to use them, not just what.
- The module runs in **Kotlin explicit-API mode** — every public declaration must state its visibility.
- Only the `ai.poly.messaging` package (excluding `internal/`) is the public contract; keep implementation in `internal/`.
- **Never log the API key or session identifiers.** Never commit credentials — set the key at runtime via `PolyMessaging.initialize(...)`.
- **No third-party dependencies without explicit review.** Core deps are intentionally limited (coroutines, OkHttp, AndroidX Security). Do not leak third-party types through the public API (`implementation`, not `api`).

## Java compatibility

The public API must stay pleasant from Java: keep `@JvmStatic`/`@JvmOverloads`/builders/callback
overloads, never make `suspend`/`Flow`/`kotlin.Result` the only path. See
`docs/ANDROID_SDK_IMPLEMENTATION_PLAN.md` → *Java Compatibility Plan*.

## Examples

When you implement or change an SDK behavior, **mirror it across both example ladders**
(Jetpack **Compose** and Android **Views**).
Copy production-shaped components from `examples/compose/06-fullreference/` or
`examples/views/06-fullreference/` rather than inventing new ones, and keep the README in sync.

## Commit conventions

[Conventional Commits](https://www.conventionalcommits.org). PR titles are CI-gated.

| Type | Effect (pre-1.0) |
|---|---|
| `feat:` | minor bump |
| `fix:` | patch bump |
| `feat!:` / `BREAKING CHANGE:` | minor (pre-1.0) / major (post-1.0) |
| `docs` / `chore` / `test` / `refactor` / `style` / `ci` / `build` / `perf` | no release |

Squash-merge to `main`; end the title with the PR number, e.g. `fix(wire): … (#12)`.

## Releases (maintainers)

1. Bump `VERSION_NAME` in `gradle.properties` (single source of truth) and add a `CHANGELOG.md` entry. **Never bump in a feature PR.**
2. Tag `vX.Y.Z` and push the tag.
3. The `release.yml` workflow publishes the AAR to Maven Central (Sonatype Central Portal). For a local dry-run: `./gradlew publishToMavenLocal`.
