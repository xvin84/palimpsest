#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub release.
# The notes are this version's section of CHANGELOG.ru.md followed by CHANGELOG.md;
# the app's self-updater shows them in Settings → Updates.
#
#   scripts/release.sh          # build, test and publish the version in app/build.gradle.kts
#   scripts/release.sh --dry    # build and print the notes, publish nothing
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
tag="v$version"
apk="build/palimpsest-$version.apk"
notes="build/release-notes-$version.md"

grep -q "palimpsest.keystore" ~/.gradle/gradle.properties 2>/dev/null || {
    echo "No release key in ~/.gradle/gradle.properties: published builds must be signed with the permanent key." >&2
    exit 1
}

section() { # prints the "## <version>" section of a changelog without its heading
    awk -v v="$version" '$0 ~ "^## "v" " {on=1; next} /^## / {on=0} on' "$1"
}

./gradlew --quiet :core:test :app:assembleRelease
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$apk"

# After scripts/rotate-key.sh: sign with the new key plus the rotation lineage (APK Signature Scheme
# v3 only — the app needs Android 13, which verifies v3). The old debug-certificate key only vouches
# for the new one inside the lineage, so Play Protect sees a proper certificate and old installs
# still update in place.
prop() { grep "^$1=" ~/.gradle/gradle.properties | cut -d= -f2-; }
if grep -q '^palimpsest.keystore2=' ~/.gradle/gradle.properties; then
    bt=$(ls -d ~/Android/Sdk/build-tools/* | sort -V | tail -1)
    "$bt/apksigner" sign --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
        --rotation-min-sdk-version 28 \
        --ks "$(prop palimpsest.keystore)" --ks-key-alias "$(prop palimpsest.key.alias)" \
        --ks-pass "pass:$(prop palimpsest.keystore.password)" --key-pass "pass:$(prop palimpsest.key.password)" \
        --next-signer --ks "$(prop palimpsest.keystore2)" --ks-key-alias "$(prop palimpsest.key2.alias)" \
        --ks-pass "pass:$(prop palimpsest.keystore2.password)" --key-pass "pass:$(prop palimpsest.keystore2.password)" \
        --lineage "$(prop palimpsest.lineage)" "$apk"
    "$bt/apksigner" verify --print-certs "$apk" | grep -m1 "certificate DN"
else
    echo "Note: still signed with the debug certificate; run scripts/rotate-key.sh once (Play Protect blocks such APKs)." >&2
fi
{
    section CHANGELOG.ru.md
    echo
    echo "---"
    echo
    section CHANGELOG.md
} > "$notes"

echo "APK:   $apk ($(du -h "$apk" | cut -f1))"
echo "Notes: $notes"
if [[ "${1:-}" == "--dry" ]]; then
    cat "$notes"
    exit 0
fi

git push origin HEAD --tags
# Not «latest» yet: the desktop CI marks it so once the computer packages and the pacman repo are
# attached, so the phone's updater, pacman and the download links all switch over together.
gh release create "$tag" "$apk" --title "Palimpsest $version" --notes-file "$notes" --latest=false
