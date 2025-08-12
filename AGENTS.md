# Repository Guidelines

## Project Structure & Module Organization
This repository is an IntelliJ Platform plugin built with Gradle Kotlin DSL. Main plugin code lives under `src/main/kotlin/org/knifefish/dependency/helper`, with UI and startup entry points in `toolWindow/` and `startup/`. Plugin metadata and bundled resources live in `src/main/resources`, especially `META-INF/plugin.xml` and `messages/MyBundle.properties`. Tests are in `src/test/kotlin`, and fixture data for IDE tests is in `src/test/testData`.

## Build, Test, and Development Commands
Use the Gradle wrapper so plugin and Kotlin versions stay aligned.

- `./gradlew buildPlugin` builds the distributable plugin ZIP in `build/distributions/`.
- `./gradlew check` runs the test suite and standard verification tasks.
- `./gradlew verifyPlugin` runs IntelliJ Plugin Verifier checks against the configured IDE.
- `./gradlew runIde` launches a sandbox IDE with the plugin installed for manual testing.

The checked-in `.run/` configurations mirror these tasks for IDE-based workflows.

## Coding Style & Naming Conventions
Write Kotlin with 4-space indentation and keep packages under `org.knifefish.dependency.helper`. Match the existing naming style: `MyProjectService`, `MyToolWindowFactory`, and `MyProjectActivity` use PascalCase for types and lowerCamelCase for methods and properties. Keep plugin-facing strings in `MyBundle.properties` when they need localization. Prefer small, focused classes and keep `plugin.xml` registrations synchronized with code moves or renames.

## Testing Guidelines
Tests use JUnit 4 with IntelliJ Platform test fixtures via `BasePlatformTestCase`. Name test files `*Test.kt` and test methods with descriptive `test...` names such as `testRename`. Put sample files and rename fixtures under `src/test/testData`, and keep `getTestDataPath()` accurate when adding new fixture folders. Run `./gradlew check` before opening a pull request.

## Commit & Pull Request Guidelines
Recent history uses short, imperative subjects like `ui settings` and `Template cleanup`. Keep commit titles concise, focused, and under roughly one line; split unrelated work into separate commits. Pull requests should describe user-visible changes, list verification performed (`check`, `verifyPlugin`, or manual `runIde` testing), and link any related issue. Include screenshots only when UI behavior changes, such as tool window updates.

## Configuration Tips
The project targets Java 21 in CI and the IntelliJ IDEA platform version declared in `build.gradle.kts`. Update `gradle.properties`, `plugin.xml`, and changelog entries together when preparing a release.
