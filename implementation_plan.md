# Implementation plan

Consume pinned thia-gestalts schemas and Olymp’s versioned interface. Consult the original gestalts repo’s `plugins/quick-note`, `plugins/note-queue` and note editor for behavior. Keep the Android project independently buildable.

1. Start with a native Android capture screen; Kotlin and Compose are the provisional implementation. Keep domain records, identity vectors, storage transactions and sync reconciliation free of Activity, Context and UI references. Keep Android image selection, lifecycle and secure key storage in platform adapters.
2. Implement durable local note and attachment writes. Copy a selected image into app-managed storage so it still opens after process restart. Make save feedback reflect the durable local commit. Preserve fields the small editor does not expose.
3. Build the mobile editor for create/read/update/delete and Queue navigation. Sample eligible notes, handle a small or empty base, and refresh after editing or deleting the current item. Persist the no-queue opt-out. Keep the landing screen focused on capture.
4. Add Olymp sign-in, secure local unlock handling and automatic retry on resume/reconnection. Verify full replica and attachment completion independently from the displayed notes. Keep account data isolated on sign-out.
5. Test on an installed Android build with offline capture, process death and a returning network. Run the same identity and sync scenarios as desktop, including an unknown academic record. Document the few Android-specific interfaces an iOS client would have to implement.

## Android MVP build and verification

The signed APK is `build/gestalter-mobile.apk` (package `thia.gestalter.mobile`, version 0.1.0, Android 8.0 and later, ARM64 and x86-64). This build uses the workspace debug signing key. Its SHA-256 is `b6981f1cab5915a1fee6259009a34e65221fb9847596052535b1f64d6a5ed12c`.

### Build and check commands

The Linux build uses a JDK (17 or later), SDK Platform 35, Build Tools 35.0.0, Android NDK 28.2.13676358, Rust 1.91.1 with the two Android targets, and `zip`. The APK build is self-contained with respect to the sibling checkouts. The host check additionally uses Node 26, CPython 3.14 with `jsonschema`, an Olymp 1.1.0 service binary and the desktop checkout. Override the latter two paths with `OLYMP_BINARY` and `GESTALTER_DESKTOP`.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
CARGO_NET_OFFLINE=true scripts/build-apk.sh
CARGO_NET_OFFLINE=true scripts/check.sh
cargo clippy --offline --locked --manifest-path native/Cargo.toml --all-targets -- -D warnings

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose build/gestalter-mobile.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 build/gestalter-mobile.apk
sha256sum build/gestalter-mobile.apk
adb install -r build/gestalter-mobile.apk
```

Omit `CARGO_NET_OFFLINE=true` when Cargo needs to populate a new dependency cache. The host check uses `../thia-olymp/target/debug/olymp` by default. Build that executable with `cargo build --locked --bin olymp` in the Olymp checkout if needed.

### Checks performed

- The APK built, passed v2/v3 signature and 16 KiB ZIP alignment verification, and contains both native libraries and the Capture launcher activity.
- Schema and protocol pins matched their checksums. The schema validator passed all twelve kinds, typed references and identity vectors. Rust formatting and Clippy also passed.
- The mobile replica and native crypto ran in separate JVM processes against the Olymp service over real HTTP/1.1 on an inherited Unix stream. The test enrolled with OPAQUE, adopted an offline guest note and image, then captured another image note offline and killed the process with SIGKILL. A fresh process read the note and byte-identical image and retained the pending flag.
- An interrupted upload sent one of two chunks successfully. Reopening and reconnecting uploaded the remaining chunk and confirmed the revision. The desktop Olymp adapter and SQLite backend projected the note and returned the exact PNG bytes.
- Editing a note selected through the Queue projection preserved its related records and unexposed fields. Its `no-queue` flag kept it out of later samples. Deletion of a disposable Queue note propagated to the desktop projection and remained a tombstone.
- The test cleared only its disposable service’s manifests and chunks. Mobile repopulated the service, and a fresh pinned SDK peer recovered the original unknown academic head and both ordered attachments byte-for-byte. Account switching also passed the isolation check.

### Android device checks remaining

The sandbox rejected ADB’s listener with `Operation not permitted`, so the install command could not reach a device. The APK and the host interoperability are verified. Android picker access, Keystore reopening, lifecycle-triggered sync and visual review remain device checks.

1. Install the APK and launch Gestalter. Confirm that it opens to Capture and that **Save & edit** opens the compact editor. Sign in to the same disposable Olymp account on Android and desktop.
2. Disconnect the phone from the service. For a USB-connected phone testing local Olymp, use `adb reverse tcp:8787 tcp:8787` and the default Olymp origin. Remove the reverse tunnel with `adb reverse --remove tcp:8787` to go offline during the test. For a remote HTTPS service, disconnect Wi‑Fi and mobile data.
3. Capture text and attach a locally available image. Tap **Save** and wait for **Saved on this device**. Run `adb shell am force-stop thia.gestalter.mobile`, then reopen the app while still offline. Open the note from Queue and check that its image opens.
4. Restore the connection or reverse tunnel. Wait for automatic confirmation and check the image on desktop. From Queue, edit that note, enable **Skip Queue**, and delete a second disposable note. Check the edit, exclusion and deletion after both apps confirm.
5. Review the Android UI on the device, including its one-handed layout and picker flow.

### Platform boundaries

The MVP uses native Android Views and a Java replica core with a Rust JNI crypto binding. `Profile.Vault` wraps the saved session and local keys in Android Keystore. `Notes.OpenImage` streams picked image bytes into durable encrypted chunks. `DurableFiles.DirectorySync` handles directory durability, and `Olymp.Transport` carries authenticated requests. Android startup, resume, connectivity and scheduled jobs trigger the same sync round. A future client can implement these interfaces and reuse the pinned wire contract and acceptance fixtures.

Capture requires text according to the note schema. Queue draws up to three eligible logical notes and stores `no-queue` in the existing tags extension. Account-scoped manifests retain the complete base and attachment references even when mobile has no editor for a record. Signing in can explicitly include the local workspace.
