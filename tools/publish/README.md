# Publishing to Nexus

Publishes the `Bazel for IntelliJ (Fremtind)` plugin to the internal Nexus repository so developers can install and receive updates automatically.

## Prerequisites

- Write access to `releases` in Nexus (via User Token, see below)
- Bazel installed and configured

## Nexus credentials (SSO)

Nexus uses SSO, so you cannot use your SSO password directly in scripts. Instead, generate a **User Token** from Nexus:

1. Log in to Nexus in the browser via SSO
2. Click your username (top right) → **User Token**
3. Click **Access User Token** — you get a *Name Code* and a *Pass Code*

Use those as `NEXUS_USER` and `NEXUS_PASSWORD` below.

## Publishing a new version

1. Update `VERSION` in `version.bzl` (format: `YYYYMMDD.N`)
2. Run the script:

```bash
export NEXUS_USER=<Name Code from User Token>
export NEXUS_PASSWORD=<Pass Code from User Token>
tools/publish/publish_to_nexus.sh
```

Use `--dry-run` to verify what would be uploaded without actually uploading:

```bash
tools/publish/publish_to_nexus.sh --dry-run
```

The script builds `//ijwb:ijwb_bazel_zip`, reads the stamped `since-build`/`until-build` from the built plugin.xml, uploads the ZIP, and overwrites `updatePlugins.xml` in Nexus to point to the new version.

Uploaded to:
- `…/releases/no/fremtind/idea/bazel/ijwb/{VERSION}/ijwb_bazel-{VERSION}.zip`
- `…/releases/no/fremtind/idea/bazel/ijwb/updatePlugins.xml`

## Developer setup (first time)

Go to **Settings → Plugins → ⚙ → Manage Plugin Repositories** and add:

```
https://nexus.intern.sparebank1.no/repository/releases/no/fremtind/idea/bazel/ijwb/updatePlugins.xml
```

The plugin then appears in the Marketplace tab and updates automatically going forward.

## Transitioning from a disk install

If the plugin was previously installed via **Install Plugin from Disk**, no uninstall is needed. IntelliJ matches plugins by ID. Once you add the repository URL above, IntelliJ detects the newer version from Nexus and shows a normal **Update** button. After that one update the plugin is on the automatic channel.

> **Note:** Updates are only offered to IDEs within the `since-build`/`until-build` range the plugin was built against. The current build targets IntelliJ 2026.2 (262). Users on older IDE versions need to update their IDE first.
