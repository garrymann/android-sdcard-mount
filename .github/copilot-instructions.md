# Copilot instructions

## Start with the requirements

- `docs/requirements/` is the source of truth. Begin with its `README.md`, use the traceability
  table to find the owning requirement file, and read that file in full before implementing a
  requirement. Also read `01-constraints.md` when it has not already informed the work.
- Cite only real `FR-nnn` and `C-n` identifiers in code comments, commits, and pull requests. The
  lower-case positional anchors in the documentation are links, not requirement identifiers.
- Stop and flag contradictions or a relevant open question in `96-open-questions.md`; do not quietly
  choose a behaviour that conflicts with the corpus.

## Architecture and safety boundaries

- Keep `:core-fs` pure Kotlin/JVM. It must never import `android.*` or `androidx.*`; filesystem,
  partition, SCSI, and image parsing belongs behind `BlockDevice` so it stays unit-testable.
- Do not infer capabilities from Android version, phone model, or OEM. Probe the actual device and
  filesystem at runtime, and give users a specific reason whenever access is unavailable.
- Integrity is more important than access: never enable writes to an unrecognised, encrypted,
  adoptable, or inconsistent volume. Do not replace structured failures with a generic error message.
- The primary cross-app surface is the `DocumentsProvider`; global root mounts are optional and never
  a substitute for it.

## Android and Gradle conventions

- Use Kotlin and Java 17. `:app` uses AGP 9's built-in Kotlin support: do **not** add
  `org.jetbrains.kotlin.android`, `kotlinOptions`, kapt, or annotation processors.
- The distribution flavors are `play` and `foss`. Root implementation and libsu dependencies belong
  only in `src/foss`/`fossImplementation`; shared and Play sources must not reference libsu or
  `dev.rsm.root`.
- Keep user-visible copy in Android resources. Compose UI must expose stable semantics for UI tests
  where a text assertion would be ambiguous.
- Put fast, deterministic tests under `core-fs/src/test`; put device/emulator tests under
  `app/src/androidTest`. Avoid real USB devices, root, network calls, and writable host paths in
  automated tests.

## Validation

- Run core unit tests with `./gradlew :core-fs:test`.
- Build the Play artifact with `./gradlew :app:assemblePlayDebug`.
- Run the UI suite on an API 35+ emulator with
  `./gradlew :app:connectedPlayDebugAndroidTest`.
- Run `./gradlew check` before a wider change; it also enforces that root code cannot leak into the
  Play artifact.
