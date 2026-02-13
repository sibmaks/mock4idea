# Mockito Tweaks 4 IDEA

IntelliJ IDEA plugin that speeds up routine Mockito usage in Java tests.

## Overview

Mockito Tweaks 4 IDEA adds intention actions for common test setup tasks:

- convert selected statements into Mockito stubs (`when(...).thenReturn(...)`)
- wrap method calls with `verify(...)`
- generate Mockito-based test classes for services with constructor dependencies

The plugin focuses on reducing repetitive boilerplate while keeping generated code readable.

## Key Features

- **Mock statement intention**
  transforms declaration statements into mocked values + stubbing.

- **Verify method intention**
  converts calls like `repository.getAll();` into `verify(repository).getAll();`.

- **Create test class intention**
  generates `<ClassName>Test` with:
  - `@ExtendWith(MockitoExtension.class)`
  - `@Mock` fields for constructor dependencies
  - `@InjectMocks` field for the class under test

- **Configurable mock expressions**
  define type-to-expression mappings in Settings (including primitive defaults).

## Compatibility

- IntelliJ IDEA platform: `since-build 252`
- Java support required (`com.intellij.java`)

## Development

Build locally:

```bash
./gradlew build
```

Run in sandbox IDE:

```bash
./gradlew runIde
```

## Release

GitHub Actions release workflow is triggered by pushing tags in format `vX.Y.Z`.
It creates a GitHub Release and uploads the built plugin jar artifact.
