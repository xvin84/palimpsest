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
gh release create "$tag" "$apk" --title "Palimpsest $version" --notes-file "$notes"
