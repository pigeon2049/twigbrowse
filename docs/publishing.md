# Artifact distribution plan

Status: no Central deployment, GitHub package or release has been published. This document describes intended addresses, not currently downloadable versions.

## Simplest early distribution: JitPack

Project/build page: https://jitpack.io/#pigeon2049/twigbrowse

The root `jitpack.yml` selects OpenJDK 17 and runs `mvn -B -ntp clean install`. Only the parent and three library modules are installed; live tests are opt-in and examples are not reactor modules.

After pushing a buildable tag or commit, request that version through the JitPack page or a Maven dependency. JitPack builds it on demand; no Central account or manual JAR upload is needed. Confirm the generated module list and transitive POM on the JitPack build page before documenting a version for consumers. This local setup has not been remotely published or tested yet.

Multi-module dependency:
`com.github.pigeon2049.twigbrowse:twigbrowse-spring-boot-starter:TAG_OR_COMMIT`

Repository: `https://jitpack.io`

JAR path pattern:
`https://jitpack.io/com/github/pigeon2049/twigbrowse/twigbrowse-spring-boot-starter/TAG_OR_COMMIT/twigbrowse-spring-boot-starter-TAG_OR_COMMIT.jar`

Build log pattern:
`https://jitpack.io/com/github/pigeon2049/twigbrowse/TAG_OR_COMMIT/build.log`

Use a real tag or pinned commit, not the literal placeholder. JitPack's module group includes the repository name; Maven Central retains `io.github.pigeon2049`. Choose one distribution channel in the consuming application. [Official build and multi-module guide](https://docs.jitpack.io/building/).

## Public Maven dependencies: Maven Central

Use namespace `io.github.pigeon2049` and publish these together at the same release version:

- `twigbrowse-parent` (POM)
- `twigbrowse-core`
- `twigbrowse-spring-boot-autoconfigure`
- `twigbrowse-spring-boot-starter`

Intended Central listing:
`https://central.sonatype.com/artifact/io.github.pigeon2049/twigbrowse-spring-boot-starter`

Intended repository directory after release:
`https://repo.maven.apache.org/maven2/io/github/pigeon2049/`

For example, after a future `0.1.0` release, the starter JAR path would be:
`https://repo.maven.apache.org/maven2/io/github/pigeon2049/twigbrowse-spring-boot-starter/0.1.0/twigbrowse-spring-boot-starter-0.1.0.jar`

Do not put this version in a working dependency example until it is actually published. Current usage requires local `mvn install` with `0.1.0-SNAPSHOT`.

Before publishing, verify the GitHub-derived namespace in the [Central Portal](https://central.sonatype.org/register/namespace/), create Portal credentials, choose a non-SNAPSHOT version, generate source/Javadoc artifacts and signatures, then configure the [Central publishing Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/). The plugin does not generate sources, Javadocs or GPG signatures for you. Publication automation and credentials are not configured in this project yet.

Central is the recommended default because consumers can use ordinary Maven resolution without a project-specific repository or GitHub token.

## Direct downloads: GitHub Releases

Release page: https://github.com/pigeon2049/twigbrowse/releases

Proposed stable tag: `v0.1.0`. Proposed attachment pattern:
`https://github.com/pigeon2049/twigbrowse/releases/download/v0.1.0/twigbrowse-core-0.1.0.jar`

Attach all library JARs, their POMs, sources/Javadocs and SHA-256 checksums. The starter alone is not runnable and does not include its dependencies. Keep the CLI example, its fat JAR, fixtures and test credentials out of the library release assets.

## Optional: GitHub Packages

Registry address, if enabled later:
`https://maven.pkg.github.com/pigeon2049/twigbrowse`

GitHub's Maven registry requires authentication even for installing public packages, which adds friction to a public starter. Keep it optional rather than asking every consumer to configure a GitHub token. [GitHub Maven registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).

## What normal builds include

The root reactor has only the parent and three library modules. `research/` and `examples/` are intentionally absent from `<modules>` and are not dependencies. The CLI is built with its own POM after local installation; it also sets `maven.deploy.skip=true`.

No deploy, GitHub release or upload is triggered by `mvn verify`, `mvn install` or the example build.
