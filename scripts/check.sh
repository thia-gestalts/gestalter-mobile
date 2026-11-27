#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

(cd vendor && sha256sum --check SHA256SUMS)
pin=$(python3 -c 'import json; print(json.load(open("vendor/thia-gestalts.pin.json"))["releaseSha256"])')
gestalts=$(cd vendor/thia-gestalts
    export LC_ALL=C
    sha256sum */v[123].json core.md examples/v[123]/*.json scripts/validate_examples.py | sha256sum)
[[ "${gestalts%% *}" == "$pin" ]]
python3.14 vendor/thia-gestalts/scripts/validate_examples.py
cargo fmt --manifest-path native/Cargo.toml --check
cargo build --locked --manifest-path native/Cargo.toml
mkdir -p build/host
javac --release 17 -cp vendor/gson-2.11.0.jar -d build/host src/main/java/thia/gestalter/core/*.java \
    tests/MobilePeer.java tests/NotesTest.java
java -Djava.library.path=native/target/debug -cp build/host:vendor/gson-2.11.0.jar thia.gestalter.core.NotesTest
node --test --test-isolation=none tests/interoperability.mjs
