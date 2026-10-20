#!/usr/bin/env bash
# One-time: moves releases off the «Android Debug» certificate, which Google Play Protect blocks
# on many phones («blocked for security reasons»), to a proper key — without breaking updates.
#
# APK Signature Scheme v3 key rotation: a lineage file, signed by the old key, says «the new key
# replaces me». Phones that have the old build installed accept the new one as an update (Android 9+;
# Palimpsest needs Android 13 anyway).
#
# Creates ~/.config/palimpsest/release-2026.jks and rotation.lineage, and adds palimpsest.keystore2.*
# to ~/.gradle/gradle.properties. scripts/release.sh picks them up from then on.
# BACK UP release.jks, release-2026.jks and rotation.lineage — losing them means users must reinstall.
set -euo pipefail
dir=~/.config/palimpsest
props=~/.gradle/gradle.properties
bt=$(ls -d ~/Android/Sdk/build-tools/* | sort -V | tail -1)

if grep -q '^palimpsest.keystore2=' "$props" 2>/dev/null; then echo "Already rotated (palimpsest.keystore2 is set)."; exit 0; fi
prop() { grep "^$1=" "$props" | cut -d= -f2-; }
old_ks=$(prop palimpsest.keystore); old_pw=$(prop palimpsest.keystore.password)
old_alias=$(prop palimpsest.key.alias); old_kpw=$(prop palimpsest.key.password)

cp -n "$dir/release.jks" "$dir/release.jks.bak-$(date +%F)"
pw=$(openssl rand -base64 32 | tr -d '/+=' | head -c 32)
keytool -genkeypair -keystore "$dir/release-2026.jks" -storetype PKCS12 -storepass "$pw" -keypass "$pw" \
    -alias palimpsest -keyalg RSA -keysize 4096 -validity 11000 -dname "CN=Palimpsest, OU=xvin84, O=xvin84"
chmod 600 "$dir/release-2026.jks"
"$bt/apksigner" rotate --out "$dir/rotation.lineage" \
    --old-signer --ks "$old_ks" --ks-key-alias "$old_alias" --ks-pass "pass:$old_pw" --key-pass "pass:$old_kpw" \
    --new-signer --ks "$dir/release-2026.jks" --ks-key-alias palimpsest --ks-pass "pass:$pw" --key-pass "pass:$pw"
cat >> "$props" <<PROPS

# New release key (APK Signature Scheme v3 rotation from the debug-certificate key). Back up the keystore and the lineage.
palimpsest.keystore2=$dir/release-2026.jks
palimpsest.keystore2.password=$pw
palimpsest.key2.alias=palimpsest
palimpsest.lineage=$dir/rotation.lineage
PROPS
"$bt/apksigner" lineage --in "$dir/rotation.lineage" --print-certs | grep -E "DN|Signer"
echo "Done. Back up $dir now (release.jks, release-2026.jks, rotation.lineage)."
