#!/usr/bin/env bash
# Publishes the Bazel for IntelliJ (Fremtind) plugin to Nexus.
#
# Prerequisites:
#   export NEXUS_USER=<your-username>
#   export NEXUS_PASSWORD=<your-password>
#
# Usage:
#   tools/publish/publish_to_nexus.sh [--dry-run]
#
# After publishing, developers add the repository URL printed at the end to
# IntelliJ Settings → Plugins → ⚙ → Manage Plugin Repositories.
# Or set it centrally via -Didea.plugin.hosts=<url> in idea.properties.
#
# NOTE: This script assumes 'releases' is a raw hosted Nexus repository with
# anonymous read enabled. If it is a Maven2 repository, PUT uploads still work
# but Nexus may require a valid Maven coordinate path — the path used here
# follows Maven coordinate conventions for that reason.

set -euo pipefail

NEXUS_BASE="https://nexus.intern.sparebank1.no/repository/releases"
NEXUS_PATH="no/fremtind/idea/bazel/ijwb"
PLUGIN_ID="no.fremtind.idea.bazel.ijwb"
PLUGIN_NAME="Bazel for IntelliJ (Fremtind)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DRY_RUN=false
for arg in "$@"; do
  [[ "$arg" == "--dry-run" ]] && DRY_RUN=true
done

if [[ "$DRY_RUN" == false ]]; then
  : "${NEXUS_USER:?NEXUS_USER must be set (export NEXUS_USER=...)}"
  : "${NEXUS_PASSWORD:?NEXUS_PASSWORD must be set (export NEXUS_PASSWORD=...)}"
fi

VERSION=$(sed -n 's/^VERSION = "\(.*\)"/\1/p' "$REPO_ROOT/version.bzl")
[[ -n "$VERSION" ]] || { echo "ERROR: Could not read VERSION from version.bzl"; exit 1; }
echo "Version: $VERSION"

# Build the plugin ZIP
cd "$REPO_ROOT"
echo "Building //ijwb:ijwb_bazel_zip..."
bazel build //ijwb:ijwb_bazel_zip

ZIP_FILE="$REPO_ROOT/bazel-bin/ijwb/ijwb_bazel.zip"
[[ -f "$ZIP_FILE" ]] || { echo "ERROR: ZIP not found at $ZIP_FILE"; exit 1; }

# Extract plugin.xml from inside the plugin JAR inside the deploy ZIP,
# to read the stamped since-build and until-build values.
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

JAR_ENTRY=$(unzip -l "$ZIP_FILE" | awk '/ijwb\/lib\// && /\.jar$/ {print $NF}' | grep -v "fast_build" | head -1)
[[ -n "$JAR_ENTRY" ]] || { echo "ERROR: No plugin JAR found in $ZIP_FILE"; exit 1; }
echo "Reading plugin.xml from $JAR_ENTRY"

unzip -p "$ZIP_FILE" "$JAR_ENTRY" > "$TMPDIR/plugin.jar"
PLUGIN_XML=$(unzip -p "$TMPDIR/plugin.jar" META-INF/plugin.xml)

SINCE_BUILD=$(echo "$PLUGIN_XML" | sed -n 's/.*since-build="\([^"]*\)".*/\1/p' | head -1)
UNTIL_BUILD=$(echo "$PLUGIN_XML" | sed -n 's/.*until-build="\([^"]*\)".*/\1/p' | head -1)
# Use the full stamped version from plugin.xml (e.g. "20260925.1-api-version-262"), not
# the plain version from version.bzl. updatePlugins.xml and the ZIP's plugin.xml must agree
# exactly, otherwise IntelliJ's version comparator will either offer a phantom update or
# refuse to install with "older than currently installed" even when versions look equal.
STAMPED_VERSION=$(echo "$PLUGIN_XML" | sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' | head -1)
[[ -n "$STAMPED_VERSION" ]] && VERSION="$STAMPED_VERSION"
[[ -n "$SINCE_BUILD" ]] || { echo "ERROR: Could not parse since-build from plugin.xml"; exit 1; }
echo "Stamped version: $VERSION"
echo "Build range: since-build=$SINCE_BUILD, until-build=${UNTIL_BUILD:-(none)}"

ZIP_FILENAME="ijwb_bazel-${VERSION}.zip"
ZIP_URL="$NEXUS_BASE/$NEXUS_PATH/$VERSION/$ZIP_FILENAME"
XML_URL="$NEXUS_BASE/$NEXUS_PATH/updatePlugins.xml"

# Generate updatePlugins.xml
XML_FILE="$TMPDIR/updatePlugins.xml"
IDEA_VERSION_ATTR="since-build=\"$SINCE_BUILD\""
[[ -n "$UNTIL_BUILD" ]] && IDEA_VERSION_ATTR="$IDEA_VERSION_ATTR until-build=\"$UNTIL_BUILD\""

cat > "$XML_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="$PLUGIN_ID"
          url="$ZIP_URL"
          version="$VERSION">
    <idea-version $IDEA_VERSION_ATTR/>
    <name>$PLUGIN_NAME</name>
  </plugin>
</plugins>
EOF

echo ""
echo "updatePlugins.xml:"
cat "$XML_FILE"
echo ""

if [[ "$DRY_RUN" == true ]]; then
  echo "[dry-run] Would upload:"
  echo "  $ZIP_FILE → $ZIP_URL"
  echo "  $XML_FILE → $XML_URL"
  exit 0
fi

echo "Uploading plugin ZIP → $ZIP_URL"
ZIP_STATUS=$(curl --silent --output /dev/null --write-out "%{http_code}" \
     --user "$NEXUS_USER:$NEXUS_PASSWORD" \
     --upload-file "$ZIP_FILE" \
     "$ZIP_URL")

if [[ "$ZIP_STATUS" == "409" ]]; then
  echo "Version $VERSION already exists in Nexus — skipping ZIP upload."
elif [[ "$ZIP_STATUS" != "200" && "$ZIP_STATUS" != "201" && "$ZIP_STATUS" != "204" ]]; then
  echo "ERROR: ZIP upload failed with HTTP $ZIP_STATUS"
  exit 1
else
  echo " done (HTTP $ZIP_STATUS)"
fi

# updatePlugins.xml lives at a fixed path, so delete the old one before uploading.
# Nexus releases repos have redeploy disabled (409 on PUT if the file exists).
echo "Deleting old updatePlugins.xml from Nexus..."
DELETE_STATUS=$(curl --silent --output /dev/null --write-out "%{http_code}" \
     --user "$NEXUS_USER:$NEXUS_PASSWORD" \
     --request DELETE \
     "$XML_URL")
echo "  DELETE status: $DELETE_STATUS"
if [[ "$DELETE_STATUS" != "204" && "$DELETE_STATUS" != "404" ]]; then
  echo "ERROR: DELETE failed with HTTP $DELETE_STATUS — check that your Nexus user has the 'nx-repository-admin-*-delete' privilege or that the releases repo allows deletion."
  exit 1
fi

echo "Uploading updatePlugins.xml → $XML_URL"
curl --fail --silent --show-error \
     --user "$NEXUS_USER:$NEXUS_PASSWORD" \
     --upload-file "$XML_FILE" \
     "$XML_URL"
echo " done"

echo ""
echo "Published $PLUGIN_NAME $VERSION to Nexus."
echo ""
echo "Plugin repository URL for IntelliJ:"
echo "  $XML_URL"
