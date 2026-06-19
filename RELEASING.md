# Releasing `com.vonage:client-library`

The library is published to [Maven Central](https://central.sonatype.com/) through the **Sonatype Central Portal**. The legacy OSSRH staging service at `oss.sonatype.org` has been retired and now returns `402 Payment Required` for upload requests; uploads go to `central.sonatype.com` instead via the [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin) Gradle plugin.

## Prerequisites

### JDK
Gradle must run with JDK 17. Newer JDKs (e.g. Java 25) cause build failures. Install [Azul Zulu 17](https://www.azul.com/downloads/?version=java-17&package=jdk) and either set `JAVA_HOME` in your shell or let `scripts/publish.sh` pick up the default location at `/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home`.

### Central Portal user token
1. Sign in at <https://central.sonatype.com> with the Sonatype account that owns the `com.vonage` namespace.
2. Open **Account → Generate User Token**.
3. The generated `username` / `password` go into `local.properties` as `centralUsername` / `centralPassword`. These are *not* the same as your portal login.

### GPG signing key
Artifacts must be signed before upload. Export your GPG secret key as an ASCII-armored file into the `publishing/` directory:

```bash
GNUPGHOME=$(pwd)/publishing gpg --batch --pinentry-mode loopback \
  --passphrase "<your-passphrase>" \
  --export-secret-keys --armor <KEY_ID> > publishing/secring.asc
```

Verify the export — the file should start with `-----BEGIN PGP PRIVATE KEY BLOCK-----`. The last 8 characters of `<KEY_ID>` are used as `signing.keyId`.

The corresponding **public key** must also be uploaded to a public keyserver (e.g. `keys.openpgp.org`) so Maven Central validators can verify the signatures.

### `local.properties`
This file is gitignored and must exist at the repo root with:

```properties
# Sonatype Central Portal user token (https://central.sonatype.com/account)
centralUsername=<token-username>
centralPassword=<token-password>

# GPG signing
signing.keyId=<last-8-chars-of-gpg-key-id>
signing.password=<gpg-key-passphrase>
signing.secretKeyRingFile=publishing/secring.asc
```

The publish wrapper reads these and forwards them to Gradle as `ORG_GRADLE_PROJECT_*` environment variables. This is the only injection path that the `com.vanniktech.maven.publish` plugin can read at config time, so the wrapper script (`scripts/publish.sh`) is required for any task that needs credentials.

## Steps

### 1. Merge PRs and update the version
All changes should be on `main` before releasing. The version is defined once at the top of `client-library/build.gradle.kts`:

```kotlin
val libraryVersion = "X.Y.Z"
```

Update that line and commit.

### 2. Run tests
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home \
  ./gradlew :client-library:testDebugUnitTest
```

All tests must pass before continuing.

### 3. Smoke-test the publish locally
This builds the AAR, generates sources/javadoc jars, and signs everything, but installs to your local Maven cache instead of uploading:

```bash
scripts/publish.sh :client-library:publishToMavenLocal
```

Inspect `~/.m2/repository/com/vonage/client-library/<version>/` and confirm you see:
* `client-library-<version>.aar` and `.aar.asc`
* `client-library-<version>-sources.jar` and `.asc`
* `client-library-<version>-javadoc.jar` and `.asc`
* `client-library-<version>.pom` and `.pom.asc`
* `client-library-<version>.module` and `.module.asc`

### 4. Upload to the Central Portal
```bash
scripts/publish.sh :client-library:publishToMavenCentral
```

This signs all artifacts and uploads them to a new deployment on the Central Portal.

If you want the deployment auto-released as soon as Central Portal validation passes, use:

```bash
scripts/publish.sh :client-library:publishAndReleaseToMavenCentral
```

### 5. Promote the deployment (manual release only)
1. Open <https://central.sonatype.com/publishing/deployments>.
2. Locate the new deployment for `com.vonage:client-library:<version>`. It runs through `PENDING → VALIDATING → VALIDATED` automatically.
3. Once it shows `VALIDATED`, click **Publish** to release it to Maven Central.
4. If validation fails, click **Drop** to remove it, fix the issue, and re-run step 4.

Propagation to Maven Central typically completes within 10–30 minutes after publishing.

### 6. Tag the release
```bash
git tag vX.Y.Z
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
| `402 Payment Required` on upload | You're hitting the legacy OSSRH endpoint. Make sure `client-library/build.gradle.kts` uses the `mavenPublishing { publishToMavenCentral() }` block (vanniktech plugin), not the old `publishing { repositories { maven { url = "...oss.sonatype.org..." } } }` block. |
| `error: missing keys in local.properties` | The publish wrapper found `local.properties` but is missing one of the required keys. Names are listed in the error output. |
| `Cannot perform signing task ... no configured signatory` | Run via `scripts/publish.sh`, not raw `./gradlew`. The signing key is injected via the wrapper. |
| Build fails with a number like `25.0.2` for "What went wrong" | Wrong JDK — set `JAVA_HOME` to JDK 17 (see Prerequisites). |
| Deployment validation fails on the Portal: missing checksums / sources / javadoc | Likely a stale `client-library/build.gradle.kts`. Confirm the `mavenPublishing` block calls `publishToMavenCentral()`, `signAllPublications()`, and `configure(AndroidSingleVariantLibrary(..., sourcesJar = true, publishJavadocJar = true))`. |
| Deployment validation fails: `signature validation failed` | The public half of your signing key isn't on a public keyserver. Upload it to `keys.openpgp.org` (or another well-known keyserver) and re-deploy. |
| `401 Unauthorized` on upload | `centralUsername`/`centralPassword` in `local.properties` are wrong or expired. Regenerate the user token at <https://central.sonatype.com/account>. |
