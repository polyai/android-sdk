## Summary

<!-- What does this change do, in one or two sentences? -->

## Motivation

<!-- Why is this needed? Closes # -->

## Changes

<!-- Bullet the notable changes. -->

## Test strategy

- [ ] `./gradlew build` (compile + lint + unit tests + apiCheck) passes
- [ ] `./gradlew test` passes
- [ ] If the **public API** changed: `./gradlew apiDump` run and the `.api` diff reviewed; example apps updated and `./scripts/build-all.sh` passes
- [ ] Ran a sample app on an emulator/device
- [ ] If the **wire protocol / reconnection** changed: tested against a live Agent Studio environment

## Checklist

- [ ] Conventional Commits PR title
- [ ] No third-party dependencies added without review (and none leaked through the public API)
- [ ] No API keys / session IDs / credentials in the diff
- [ ] README updated if public API or behavior changed
- [ ] **Compose and Views example ladders mirrored** for any SDK behavior change
- [ ] `VERSION_NAME` not touched (release-only)

## Screenshots / recordings

<!-- For UI/example changes. -->

## Logs

<!-- Redact API keys and session identifiers. -->
