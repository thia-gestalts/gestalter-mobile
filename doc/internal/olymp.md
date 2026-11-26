# Connect Gestalter Mobile to Olymp

In the Android app, open **Account**, replace the default loopback value in **Olymp origin** with `https://gestalter.koljasam.com`, and enter your Olymp login name and password. Choose **Sign in**, or **Create account** if you are new to Olymp. Use **Include notes from this local workspace** to move guest notes into that account.

When the Olymp domain changes, let pending edits sync to the old origin, then **Sign out**. Enter the new HTTPS origin in **Account** and sign in with the same credentials after the service has moved. Disable guest-note inclusion if you only want to reopen the existing account. Android uses a separate local replica for each Olymp origin. Confirm that notes and attachments load from the new origin.

When moving the service to a new VPS, preserve the Olymp SQLite database and its matching `opaque-setup-v1` from `/var/lib/olymp`. They keep existing accounts and synced data available at the new HTTPS endpoint.
