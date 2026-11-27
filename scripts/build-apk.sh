#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}}
tools="$sdk/build-tools/35.0.0"
platform="$sdk/platforms/android-35/android.jar"

# The Olymp origin is fixed per build; users never type it. Override with OLYMP_ORIGIN,
# e.g. OLYMP_ORIGIN=http://10.0.2.2:8787 for a local Olymp reached from the emulator.
origin=${OLYMP_ORIGIN:-https://gestalter.koljasam.com}
if [[ ! "$origin" =~ ^(https://[A-Za-z0-9.-]+|http://(localhost|127\.0\.0\.1|10\.0\.2\.2|\[::1\]))(:[0-9]+)?$ ]]; then
    echo "OLYMP_ORIGIN must be an HTTPS origin (or HTTP on loopback) without a path: $origin" >&2
    exit 1
fi

if (( $# == 0 )); then set -- arm64-v8a x86_64; fi
scripts/build-native.sh "$@"
mkdir -p build/apk/{classes,dex,package}
rm -rf build/apk/classes/* build/apk/dex/* build/apk/package/* build/apk/generated
gson=vendor/gson-2.11.0.jar
(cd vendor && sha256sum --check SHA256SUMS)

"$tools/aapt2" compile --dir src/main/res -o build/apk/resources.zip
"$tools/aapt2" link -I "$platform" --manifest src/main/AndroidManifest.xml \
    -o build/apk/package/resources.apk build/apk/resources.zip

mkdir -p build/apk/generated/thia/gestalter/android
cat > build/apk/generated/thia/gestalter/android/BuildConfig.java <<EOF2
package thia.gestalter.android;

final class BuildConfig {
    private BuildConfig() {}
    static final String OLYMP_ORIGIN = "$origin";
}
EOF2
mapfile -t sources < <(find src/main/java build/apk/generated -name '*.java' | sort)
javac --release 8 -Xlint:-options -cp "$platform:$gson" -d build/apk/classes "${sources[@]}"
jar cf build/apk/classes.jar -C build/apk/classes .
"$tools/d8" --min-api 26 --lib "$platform" --release --output build/apk/dex build/apk/classes.jar "$gson"
for abi in "$@"; do
    mkdir -p "build/apk/package/lib/$abi"
    cp "build/jni/lib/$abi/libgestalter_crypto.so" "build/apk/package/lib/$abi/"
done
cp build/apk/dex/classes*.dex build/apk/package/
(
    cd build/apk/package
    zip -q -0 resources.apk classes*.dex
    zip -q -r -0 resources.apk lib
)
"$tools/zipalign" -f -P 16 4 build/apk/package/resources.apk build/apk/aligned.apk

# The local debug key is reused for adb install -r. Set APK_KEYSTORE, APK_KEY_ALIAS
# and APK_KEY_PASSWORD for a separately managed signing key.
store=${APK_KEYSTORE:-build/apk/debug.p12}
alias=${APK_KEY_ALIAS:-androiddebugkey}
export GESTALTER_SIGNING_PASSWORD=${APK_KEY_PASSWORD:-android}
if [[ ! -f "$store" ]]; then
    if [[ -n ${APK_KEYSTORE:-} ]]; then echo "Signing key not found: $store" >&2; exit 1; fi
    keytool -genkeypair -noprompt -keystore "$store" -storetype PKCS12 -storepass android \
        -alias "$alias" -dname 'CN=Android Debug' -keyalg RSA -keysize 2048 -validity 10000
fi
"$tools/apksigner" sign --ks "$store" --ks-key-alias "$alias" \
    --ks-pass env:GESTALTER_SIGNING_PASSWORD --key-pass env:GESTALTER_SIGNING_PASSWORD \
    --out build/gestalter-mobile.apk build/apk/aligned.apk
"$tools/apksigner" verify build/gestalter-mobile.apk
"$tools/zipalign" -c -P 16 4 build/gestalter-mobile.apk
echo "APK: $PWD/build/gestalter-mobile.apk"
