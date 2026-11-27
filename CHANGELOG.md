<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Dependency Helper Changelog

## [Unreleased]

## [1.1.0] - 2026-11-27

### Fixed

- Register the Gradle sync listener independently of Maven and refresh only the project that completed the sync.
- Align Gradle, Maven, and npm code with the SDK's nullability contracts.

### Changed

- Target IntelliJ Platform build 262 with Java 25 for compilation and testing. Keep Java 21 as the Gradle runtime in CI.
- Update the Gradle wrapper to 9.9.2, Kotlin and its serialization compiler plugin to 2.4.21, and the IntelliJ Platform Gradle plugin to 2.20.1.
- Use the IDE-bundled Kotlin serialization runtime.
- Migrate Jupiter tests to JUnit 4.13.2 to match the JetBrains template.
- Update free-disk-space to v2.0.0 and the release workflow's setup-java action to v6. Upload the plugin ZIP without wrapping it in another archive.
- Ignore generated out directories.

## [1.0.5] - 2026-07-21

- Fix disposed editor access during async refresh

## [1.0.4] - 2026-06-23

### Fixed

- Optimize dependency upgrade hints and popup performance
- Fix npm quick doc upgrade action

## [1.0.3] - 2026-06-10

### Fixed

- Fix npm package dependency hint alignment

## [1.0.2] - 2026-05-19

### Fixed

- Refine dependency analyzer file targeting

## [1.0.1] - 2026-05-19

### Added

- Support Maven plugin update hits
- Support Gradle plugin update hits

## [1.0.0] - 2026-05-17

### Added

- Initial release of IntelliJ Dependency Helper Plugin. support Maven and Gradle dependency analysis and package search, Npm package.json version hints.

[Unreleased]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.0.5...v1.1.0
[1.0.5]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/knife-fish/intellij-plugin-dependency-helper/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/knife-fish/intellij-plugin-dependency-helper/commits/v1.0.0
