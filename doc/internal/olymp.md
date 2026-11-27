# Connect Gestalter Mobile to Olymp

The Olymp origin is fixed when the APK is built. Users never enter it. `scripts/build-apk.sh` reads `OLYMP_ORIGIN` and defaults to `https://gestalter.koljasam.com`. It accepts only an HTTPS origin without a path, or HTTP on loopback or the emulator host `10.0.2.2` (for example `OLYMP_ORIGIN=http://10.0.2.2:8787 mise run dev` against a local Olymp).

Run `mise run apk` to build the signed, versioned APK under `build/`. With an Android device connected and USB debugging authorized, `mise run dev` builds, installs, and opens the app.

In the Android app, open **Account** and enter your Olymp login name and password. Choose **Sign in**, or **Create account** if you are new to Olymp. Use **Include notes from this local workspace** to move guest notes into that account.

While the session is valid, **Account** only shows the account and **Sign out**; the app never asks for the password again, because each sign-in creates another 30-day Olymp session. When Olymp rejects the session (HTTP 401, e.g. after 30 days or a revocation), sync pauses, pending notes stay on the device, and **Account** asks for the password to **Sign in again** to the same account.

When the Olymp domain changes, let pending edits sync to the old origin, build and install an APK with the new `OLYMP_ORIGIN`, then **Sign out** (Account notes that the stored session belongs to another origin) and sign in with the same credentials after the service has moved. Disable guest-note inclusion if you only want to reopen the existing account. Android uses a separate local replica for each Olymp origin. Confirm that notes and attachments load from the new origin.

When moving the service to a new VPS, preserve the Olymp SQLite database and its matching `opaque-setup-v1` from `/var/lib/olymp`. They keep existing accounts and synced data available at the new HTTPS endpoint.
