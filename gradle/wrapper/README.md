# Wrapper provenance

`gradle-wrapper.jar` is a binary in version control, so where it came from is recorded here rather
than assumed.

| Item | Value |
|---|---|
| Gradle version | 9.6.1 (released 2026-06-26) |
| Jar size | 48 462 bytes |
| Jar SHA-256 | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| Expected SHA-256 published by Gradle | `https://services.gradle.org/distributions/gradle-9.6.1-wrapper.jar.sha256` |
| Obtained from | `https://codeload.github.com/gradle/gradle/zip/refs/tags/v9.6.1`, entry `gradle-9.6.1/gradle/wrapper/gradle-wrapper.jar` |

The two checksums were compared and matched before the jar was added. `gradlew` and `gradlew.bat`
come from the same tag, unmodified — `gradlew.bat` was rewritten upstream in Gradle 9.5, so a
hand-written or older copy would misbehave.

The distribution itself is also checksum-pinned: `gradle-wrapper.properties` carries
`distributionSha256Sum`, so a tampered or truncated `gradle-9.6.1-bin.zip` fails rather than runs.

To verify at any time:

```
shasum -a 256 gradle/wrapper/gradle-wrapper.jar          # macOS / Linux
certutil -hashfile gradle\wrapper\gradle-wrapper.jar SHA256   # Windows
```

`.gitattributes` marks `*.jar binary` and was committed before this file landed. Without it,
`core.autocrlf=true` on Windows rewrites bytes inside the jar and the wrapper stops working with a
confusing error.
