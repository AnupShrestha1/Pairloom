# Building a release

## One-time: create an upload key (you, not the repository)
Google Play App Signing holds the real app-signing key. You sign uploads with your own upload key.
Create it once and keep it, with its passwords, somewhere safe and backed up outside this
repository. If it is lost, Play support can reset it, but that takes time.

```bash
keytool -genkeypair -v -keystore ~/keys/pairloom-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias pairloom-upload
```

Then create `keystore.properties` in the project root. It is git-ignored, and so are `*.jks` and
`*.keystore`:

```properties
storeFile=../keys/pairloom-upload.jks
keyAlias=pairloom-upload
# Optional: leave these out and set PAIRLOOM_STORE_PASSWORD / PAIRLOOM_KEY_PASSWORD instead.
storePassword=...
keyPassword=...
```

`storeFile` is relative to the project root, or absolute. Without `keystore.properties` the
release build is still produced, but unsigned.

## Each release
1. Raise `versionCode` (by one) and set `versionName` in `app/build.gradle.kts`.
2. Run the tests and build the bundle:
   ```bash
   ./gradlew testDebugUnitTest bundleRelease
   ```
   The bundle is `app/build/outputs/bundle/release/app-release.aab`. Release builds run R8 (code
   shrinking and optimisation) and remove unused resources.
3. Check the shrunk code on two phones before uploading. The `profile` build type is the same
   release code signed with the debug key, so it installs next to nothing else:
   ```bash
   ./gradlew assembleProfile
   adb install -r app/build/outputs/apk/profile/app-profile.apk
   ```
   Try Connect, a call and push-to-talk at least.
4. Upload the `.aab` in the Play Console (internal testing track first).

## Offline builds
Two release steps need artifacts that an offline Gradle cache may not have:
- **Lint vital** (`lintVitalRelease`, which runs before release builds). Run it online at least
  once per release. Offline, skip it with
  `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease`.
- **The Compose mapping file** for Play Console stack traces. It is switched off in
  `app/build.gradle.kts` (`includeComposeMappingFile`). Turn it on for builds made online, if
  wanted.

## Sizes (0.3.0)
| Build | Size |
|---|---|
| Debug APK | 12.5 MB |
| Profile APK (R8, resources shrunk) | 1.1 MB |
| Release bundle | 2.5 MB (Play delivers less, split per device) |
