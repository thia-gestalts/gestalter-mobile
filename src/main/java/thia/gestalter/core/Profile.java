package thia.gestalter.core;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class Profile {
    public interface Vault {
        byte[] read() throws Exception;
        void write(byte[] state) throws Exception;
    }

    private final Vault vault;
    private final DurableFiles disk;
    private final Path directory;
    private Olymp.Session guest;
    private volatile Replica replica;

    public Profile(Vault vault, DurableFiles disk, Path directory) throws Exception {
        this.vault = vault;
        this.disk = disk;
        this.directory = directory;
        Olymp.Session account = null;
        byte[] stored = vault.read();
        if (stored != null) {
            try {
                JsonObject config = Json.parse(stored).getAsJsonObject();
                if (config.get("account").isJsonObject())
                    account = Olymp.Session.restore(config.getAsJsonObject("account"));
                if (config.get("guest").isJsonObject())
                    guest = Olymp.Session.restore(config.getAsJsonObject("guest"));
            } finally {
                Arrays.fill(stored, (byte) 0);
            }
        }
        if (guest == null && account == null) {
            guest = newGuest();
            remember(null, guest);
        }
        replica = new Replica(disk, directory, account == null ? guest : account);
    }

    public Replica replica() { return replica; }
    public Olymp.Session account() { return replica.account(); }

    private static Olymp.Session newGuest() {
        return new Olymp.Session("local:", UUID.randomUUID().toString(), "Local workspace", null, Crypto.random(32));
    }

    private void remember(Olymp.Session active, Olymp.Session local) throws Exception {
        byte[] data = Json.bytes(Json.object("account", active == null ? null : active.secret(),
                "guest", local == null ? null : local.secret()));
        try { vault.write(data); }
        finally { Arrays.fill(data, (byte) 0); }
    }

    public synchronized void signIn(Olymp.Transport transport, String origin, String name, byte[] password,
            boolean signup, boolean includeLocal, String device) throws Exception {
        Olymp.Session account = account();
        if (account != null && (!Olymp.origin(origin).equals(account.origin) || signup
                || !account.name.equalsIgnoreCase(name.trim())))
            throw new IllegalStateException("Sign out before switching accounts.");
        Olymp.Session unlocked = Olymp.signIn(transport, origin, name, password, signup, device);
        if (account != null && !unlocked.id.equals(account.id))
            throw new IllegalStateException("This Olymp account changed. Sign out before switching accounts.");
        Replica next = new Replica(disk, directory, unlocked);
        Olymp.Session remainingGuest = guest;
        if (account == null && includeLocal) {
            replica.adoptBy(next);
            remainingGuest = null;
        }
        remember(unlocked, remainingGuest);
        guest = remainingGuest;
        replica = next;
    }

    public synchronized void signOut(Olymp.Transport transport) throws Exception {
        Olymp.Session previous = account();
        if (previous == null) return;
        // Make the local switch durable even when the service is unreachable.
        Olymp.Session local = guest == null ? newGuest() : guest;
        Replica next = new Replica(disk, directory, local);
        remember(null, local);
        guest = local;
        replica = next;
        try { Olymp.logout(transport, previous); }
        catch (java.io.IOException unavailable) { /* Local access has already been cleared. */ }
    }
}
