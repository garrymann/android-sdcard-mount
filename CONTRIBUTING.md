# Contributing

Thanks for your interest in Removable Storage Manager. This project handles other people's storage
media, so it values correctness and data integrity over features.

## Before you start

- For anything beyond a small fix, open an issue first so the approach can be agreed before you write
  code. This matters most for filesystem drivers, root code and anything that writes to media.
- Read [REQUIREMENTS.md](REQUIREMENTS.md). It explains the platform constraints that rule out many
  designs that look reasonable at first.
- Do not work on bundling GPL tools (`ntfs-3g`, `exfatprogs`, …) or a userspace exFAT implementation
  without discussing it first: both carry licensing or patent questions that are still open.

## Ground rules

- **Integrity over access.** Never enable writes to an unrecognised, encrypted, adoptable or
  inconsistent volume. Refuse rather than risk corruption.
- **Probe, don't guess.** Detect capabilities at runtime on the actual device and filesystem. Do not
  branch on Android version, phone model or OEM to infer what works.
- **No generic errors.** Every inaccessible volume gets a specific reason, and says whether root would
  help.
- `:core-fs` is pure Kotlin/JVM and must never import `android.*` or `androidx.*`.
- Root code and `libsu` belong only in the `foss` flavor (`src/foss`, `fossImplementation`). The
  `play` flavor must never reference them; `./gradlew check` enforces this.
- User-visible text goes in Android string resources.
- Cite the relevant `FR-nnn` / `C-n` ids in commits and pull requests where they apply.

## Building and testing

You need JDK 17 and an Android SDK with the API 37 platform.

| Command | Purpose |
|---|---|
| `./gradlew :core-fs:test` | Fast JVM unit tests for the filesystem core |
| `./gradlew :app:assemblePlayDebug` | Build the Play debug APK |
| `./gradlew :app:connectedPlayDebugAndroidTest` | Compose UI tests on an API 35+ emulator or device |
| `./gradlew check` | All verification, including the Play/root separation guard |

Put fast, deterministic tests under `core-fs/src/test` and device tests under `app/src/androidTest`.
Automated tests must not need real USB devices, root, network access or writable host paths.

Run `./gradlew check` before opening a pull request.

## Developer Certificate of Origin

Every commit must be signed off to certify that you wrote it or otherwise have the right to submit it
under the project's license, as described in the
[Developer Certificate of Origin](https://developercertificate.org/). Add the sign-off with:

```bash
git commit -s
```

This appends a line like `Signed-off-by: Your Name <you@example.com>`, which must match the commit
author. To sign off commits you have already made, use `git rebase --signoff main`.

## License of contributions

By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0 or later](LICENSE), the same license as the project.
