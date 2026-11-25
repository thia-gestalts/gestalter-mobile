package thia.gestalter.core;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

import static java.nio.file.StandardOpenOption.READ;

public final class MobilePeer {
    private static final String ORIGIN = "http://127.0.0.1:8787";
    private static final byte[] PASSWORD = Json.bytes("a guest learns café 🙂");
    private static final BufferedReader CONTROL = new BufferedReader(new InputStreamReader(System.in));

    private static final Olymp.Transport PIPE = (origin, path, method, headers, body) -> {
        JsonObject mapped = new JsonObject();
        headers.forEach(mapped::addProperty);
        send("request", Json.object("origin", origin, "path", path, "method", method, "headers", mapped,
                "body", body == null ? null : Crypto.encode(body)));
        String line = CONTROL.readLine();
        if (line == null) throw new EOFException();
        JsonObject response = JsonParser.parseString(line).getAsJsonObject();
        int status = response.get("status").getAsInt();
        if (status == 0) throw new IOException("Injected disconnect");
        if (status >= 300) throw new Olymp.HttpFailure(status);
        return Crypto.decode(response.get("body").getAsString());
    };

    private static final Olymp.Transport OFFLINE = (origin, path, method, headers, body) -> {
        throw new IOException("Offline");
    };

    private static void send(String type, Object value) {
        System.out.println(Json.object(type, value));
        System.out.flush();
    }

    private static void require(boolean okay, String failure) {
        if (!okay) throw new AssertionError(failure);
    }

    private static Notes.Note note(Replica replica, String text) {
        return replica.notes().stream().filter(item -> item.text.equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing note " + text));
    }

    private static Notes.Note queued(Replica replica, String text) {
        return Notes.queue(replica.notes(), new Random(19)).stream().filter(item -> item.text.equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing Queue note " + text));
    }

    private static Notes.Note save(Replica replica, String text, boolean exclude, Notes.NewImage... images)
            throws Exception {
        return replica.save(null, text, exclude, Collections.emptyList(), Arrays.asList(images));
    }

    private static void imageMatches(Replica replica, Notes.Note note, Path png) throws Exception {
        require(note.images.size() == 1, "Expected one image");
        try (InputStream in = replica.image(note.images.get(0))) {
            require(Arrays.equals(Files.readAllBytes(png), in.readAllBytes()), "Image bytes changed");
        }
    }

    private static void sync(Replica replica) throws Exception {
        replica.sync(PIPE);
        require(replica.status().startsWith("Fully synced at revision ") && !replica.pending(),
                "Sync was not confirmed");
    }

    private static void identities() throws Exception {
        Path vectors = Path.of("vendor/thia-gestalts/examples/v1/identity.json");
        JsonObject data = Json.parse(Files.readAllBytes(vectors)).getAsJsonObject();
        for (String group : Arrays.asList("equal", "distinct"))
            for (JsonElement pair : data.getAsJsonArray(group)) {
                String left = key(pair.getAsJsonArray().get(0).getAsJsonObject());
                String right = key(pair.getAsJsonArray().get(1).getAsJsonObject());
                require(left.equals(right) == group.equals("equal"), "Typed record identity mismatch");
            }
        require(Json.recordKey("x", "😀\u2028\"\\\n").equals("gestalt/[\"x\",\"😀\u2028\\\"\\\\\\n\"]"),
                "ECMAScript key escaping mismatch");
    }

    private static String key(JsonObject obj) {
        return Json.recordKey(obj.get("kind").getAsString(), obj.get("id").getAsString());
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        Path png = Path.of(args[2]);
        Path vault = directory.resolve("host-profile.json");
        DurableFiles disk = new DurableFiles(path -> {
            try (FileChannel channel = FileChannel.open(path, READ)) { channel.force(true); }
        });
        Profile profile = new Profile(new Profile.Vault() {
            public byte[] read() throws IOException { return Files.exists(vault) ? Files.readAllBytes(vault) : null; }
            public void write(byte[] data) throws IOException { disk.write(vault, data); }
        }, disk, directory.resolve("replicas"));
        Replica replica = profile.replica();
        Notes.NewImage picture = new Notes.NewImage("image/png", () -> Files.newInputStream(png));

        switch (args[0]) {
            case "enroll":
                identities();
                save(replica, "Guest capture", true, picture);
                profile.signIn(PIPE, ORIGIN, "Mobile.E2E", PASSWORD, true, true, "Gestalter Android host");
                sync(profile.replica());
                imageMatches(profile.replica(), note(profile.replica(), "Guest capture"), png);
                send("result", profile.replica().status());
                break;
            case "offline":
                try { replica.sync(OFFLINE); }
                catch (IOException expected) { /* User disconnected. */ }
                Notes.Note captured = save(replica, "Capture offline 🖼", false, picture);
                save(replica, "Disposable Queue note", false);
                imageMatches(replica, captured, png);
                require(replica.pending(), "Save must remain pending offline");
                send("result", captured.key);
                Thread.sleep(Long.MAX_VALUE);
                break;
            case "restart":
                imageMatches(replica, note(replica, "Capture offline 🖼"), png);
                require(replica.pending(), "Pending flag did not survive restart");
                require(Notes.queue(replica.notes(), new Random(19)).stream().noneMatch(n -> n.noQueue),
                        "Opted-out note appeared in Queue");
                send("result", "Offline record and image reopened");
                break;
            case "interrupted":
                try {
                    replica.sync(PIPE);
                    throw new AssertionError("Expected upload interruption");
                } catch (IOException expected) {
                    require(replica.pending() && !replica.status().startsWith("Fully synced"),
                            "Interrupted transfer reported completion");
                    imageMatches(replica, note(replica, "Capture offline 🖼"), png);
                }
                send("result", replica.status());
                break;
            case "sync":
                sync(replica);
                send("result", replica.status());
                break;
            case "queue-crud":
                Notes.Note original = queued(replica, "Desktop note");
                Notes.Note changed = replica.save(original, "Updated from Queue", true, original.images,
                        Collections.emptyList());
                replica.delete(queued(replica, "Disposable Queue note"));
                require(Notes.queue(replica.notes(), new Random(19)).stream()
                        .noneMatch(n -> n.key.equals(changed.key) || n.text.equals("Disposable Queue note")),
                        "Queue did not honor an edit or deletion");
                sync(replica);
                send("result", replica.status());
                break;
            case "recover":
                sync(replica);
                send("result", replica.status());
                break;
            case "switch":
                profile.signOut(OFFLINE);
                require(profile.account() == null && profile.replica().notes().isEmpty(), "Sign-out exposed account data");
                profile.signIn(PIPE, ORIGIN, "other", PASSWORD, true, true, "Other Android");
                sync(profile.replica());
                require(profile.replica().notes().isEmpty(), "New account inherited records");
                profile.signOut(PIPE);
                profile.signIn(PIPE, ORIGIN, "mobile.e2e", PASSWORD, false, true, "Gestalter Android reopened");
                imageMatches(profile.replica(), note(profile.replica(), "Capture offline 🖼"), png);
                sync(profile.replica());
                send("result", profile.replica().status());
                break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }
}
