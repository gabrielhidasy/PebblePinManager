# Contributing to PebblePinManager

PebblePinManager follows an **issue-based development** workflow.

## Workflow

1. **Open an Issue First**
   - Every new feature, enhancement, refactor, or bug fix starts as an issue describing the goal and acceptance criteria.
2. **Work on a Feature Branch**
   - Branch off `main` using a descriptive name referencing the issue number, e.g. `feat/issue-1-mvp-pin-manager` or `fix/issue-12-timezone-offset`.
3. **Test & Build**
   - Run Android unit tests and debug APK build:
     ```bash
     cd android && ./gradlew testDebugUnitTest assembleDebug
     ```
   - Build the `.pbw` bundle (`cd watchapp && pebble build`) and confirm zero errors/warnings.
   - Or run both inside Docker via `./scripts/build-docker.sh`.
4. **Open a Pull Request**
   - Reference the issue (`Closes #<N>`) in the PR description.
   - For PRs that affect the **Android APK** (`android/app`) or **Pebble watchapp** (`watchapp/`), add a concise 1–2 line user-facing entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) (omit CI, docs-only, and internal tooling PRs).
   - Include verification output and any watch-testing notes.
   - Keep commits clean, focused, and referencing `#<N>`.
