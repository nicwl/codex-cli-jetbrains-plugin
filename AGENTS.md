# Repository Guidelines

## Project Structure & Module Organization

- `src/main/kotlin/`: Kotlin sources for the JetBrains plugin.
- `src/main/resources/`: Plugin resources and `META-INF/plugin.xml`.
- `src/test/kotlin/`: Tests (JUnit 4 + IntelliJ test framework).
- `src/test/testData/`: Test fixtures (referenced via `@TestDataPath`).
- `gradle*`, `build.gradle.kts`, `settings.gradle.kts`: Build configuration.
- `codex/`: Reference-only copy of Codex CLI; do not modify or depend on it for plugin code.

## Build, Test, and Development Commands

- `./gradlew build`: Compile, run checks, and package the plugin.
- `./gradlew test`: Run unit and platform tests.
- `./gradlew runIde`: Launch a sandbox IDE with the plugin for manual testing.
- `./gradlew verifyPlugin`: Verify plugin against target IDEs.
- `./gradlew publishPlugin`: Publish to Marketplace (requires `PUBLISH_TOKEN`).
- `./gradlew koverXmlReport`: Generate coverage (XML is also produced on `check`).
- `./gradlew qodanaScan`: Static analysis with Qodana (optional locally).

## Coding Style & Naming Conventions

- Language: Kotlin (JVM toolchain 21). Follow Kotlin coding conventions.
- Indentation: 4 spaces; keep lines concise; organize imports.
- Naming: `PascalCase` for classes/types, `camelCase` for methods/properties, `UPPER_SNAKE_CASE` for constants.
- Files: One top-level type per `.kt` file named after the class.
- Plugin XML: Declare extensions/actions in `src/main/resources/META-INF/plugin.xml`.

## Testing Guidelines

- Frameworks: JUnit 4 + IntelliJ Platform test framework (`BasePlatformTestCase`).
- Location: Place tests in `src/test/kotlin` with `*Test.kt` suffix; fixtures in `src/test/testData`.
- Run: `./gradlew test` for headless; use `runIde` for manual verification.
- Coverage: Enforced via Kover; aim to cover new logic and extension points.

## Commit & Pull Request Guidelines

- Commits: Use short, imperative subjects (e.g., “Configure JDK”), with optional body explaining rationale. Reference issues (`#123`) when applicable.
- Branches: Prefer `feature/...`, `fix/...`, or `chore/...` for clarity.
- PRs: Include a clear description, linked issues, steps to test, and screenshots/GIFs for UI/tool window changes. Update tests and (if user-facing) `CHANGELOG.md` under “Unreleased”. Ensure `./gradlew build` passes.

## Security & Configuration Tips

- Secrets: Never commit keys. Use env vars for signing/publishing: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`.
- Scope: Do not treat `codex/` as a build input; it exists only for agent reference.
