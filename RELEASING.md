# Releasing `com.vonage:client-library`

The library is published to [Maven Central](https://central.sonatype.com/) via the OSSRH Sonatype staging repository.

## Prerequisites

### JDK
Gradle must run with JDK 17. Using a newer JDK (e.g. Java 25) will cause a build failure. Install [Azul Zulu 17](https://www.azul.com/downloads/?version=java-17&package=jdk) and prefix every `gradlew` command below with:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
```

Or prefix each command inline as shown in the steps below.

### GPG signing key
Artifacts must be signed before upload. You need a GPG key pair exported as a secret keyring file.

```bash
gpg --export-secret-keys <KEY_ID> > vonage-release.gpg
```

### `local.properties`
This file is git-ignored and must exist at the repo root with the following keys:

```properties
# Sonatype OSSRH credentials (https://oss.sonatype.org)
nexusUsername=<your-sonatype-username>
nexusPassword=<your-sonatype-password>

# GPG signing
signing.keyId=<last-8-chars-of-gpg-key-id>
signing.password=<gpg-key-passphrase>
signing.secretKeyRingFile=<absolute-path-to-vonage-release.gpg>
```

> **Note:** `local.properties` currently also contains `centralUsername` and `centralPassword` keys which are unused by the build — the build reads `nexusUsername`/`nexusPassword`.

## Steps

### 1. Merge PRs and update the version

All changes should be on `main` before releasing. Version is defined once at the top of `client-library/build.gradle.kts`:

```kotlin
val libraryVersion = "X.Y.Z"
```

Update that single line and commit.

### 2. Run tests

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home \
  ./gradlew :client-library:testDebugUnitTest
```

All tests must pass before continuing.

### 3. Build the release AAR

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home \
  ./gradlew :client-library:assembleRelease
```

Output: `client-library/build/outputs/aar/client-library-release.aar`

### 4. Publish to OSSRH staging

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home \
  ./gradlew :client-library:publishAarPublicationToOSSRHRepository
```

This signs the AAR + POM with your GPG key and uploads them to the Sonatype OSSRH staging repository.

### 5. Promote through Sonatype

1. Log in to [https://oss.sonatype.org](https://oss.sonatype.org) with your `nexusUsername`/`nexusPassword`.
2. Go to **Staging Repositories** and find your repository (named something like `comvonage-XXXX`).
3. Verify the contents look correct (AAR, POM, sources if any, signatures).
4. Click **Close** and wait for the validation checks to pass.
5. Click **Release** to promote to Maven Central.

Propagation to Maven Central typically takes 10–30 minutes.

### 6. Tag the release

```bash
git tag v X.Y.Z
git push origin vX.Y.Z
```

### 7. Create a GitHub Release

```bash
gh release create vX.Y.Z --title "vX.Y.Z" --notes "Release notes here"
```

### 8. Update the README installation snippet

In `README.md`, update the version in the installation example:

```
implementation 'com.vonage:client-library:X.Y.Z'
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Could not read property 'nexusUsername'` | Check `local.properties` has `nexusUsername` (not `centralUsername`) |
| Build fails with `What went wrong: 25.0.2` (or similar version number) | Wrong JDK — set `JAVA_HOME` to JDK 17 as described in Prerequisites |
| `signing.secretKeyRingFile` not found | Use an absolute path, not `~/...` |
| Staging repo fails validation | Ensure POM has `name`, `description`, `url`, `licenses`, `developers`, and `scm` — the build already populates these |
| `401 Unauthorized` on upload | OSSRH credentials require a separate account from your Sonatype Jira login — verify at [oss.sonatype.org](https://oss.sonatype.org) |
