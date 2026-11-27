package thia.gestalter.core;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;

public final class Replica {
    private static final int CHUNK = 262144;
    private final DurableFiles disk;
    private final Path directory;
    private final Olymp.Session session;
    private final byte[] context;
    private State state;
    private volatile String status;
    private volatile boolean rejected;

    private static final class State {
        final Manifest base;
        final long revision;
        final boolean pending;
        final String owner;

        State(Manifest base, long revision, boolean pending, String owner) {
            this.base = base;
            this.revision = revision;
            this.pending = pending;
            this.owner = owner;
        }

        static State read(JsonObject data) {
            return new State(Manifest.read(data.getAsJsonObject("base")), data.get("revision").getAsLong(),
                    data.get("pending").getAsBoolean(), Json.string(data, "owner", null));
        }

        JsonObject json() {
            return Json.object("base", base.json(), "revision", revision, "pending", pending, "owner", owner);
        }
    }

    public Replica(DurableFiles disk, Path root, Olymp.Session session) throws Exception {
        this.disk = disk;
        this.session = session;
        directory = root.resolve(Crypto.digest(Json.bytes(session.scope())));
        context = Json.bytes(Json.tuple("olymp", 1, session.id, "chunk"));
        Path file = directory.resolve("replica.json");
        state = Files.exists(file) ? State.read(Json.parse(Files.readAllBytes(file)).getAsJsonObject())
                : new State(new Manifest(), 0, false, null);
        status = session.token == null ? "Local workspace" : state.pending ? "Pending sync" : "Awaiting sync";
    }

    Olymp.Session account() { return session.token == null ? null : session; }

    public String status() { return status; }
    /** Olymp refused this session's token; only a new sign-in can resume sync. */
    boolean rejected() { return rejected; }

    public synchronized List<Notes.Note> notes() {
        return Notes.project(state.base);
    }

    private void commit(State next) throws IOException {
        disk.write(directory.resolve("replica.json"), Json.bytes(next.json()));
        state = next;
    }

    private Path chunkPath(String id) { return directory.resolve("chunks").resolve(id); }

    private byte[] loadChunk(String id) throws IOException, GeneralSecurityException {
        byte[] encrypted = Files.readAllBytes(chunkPath(id));
        if (!id.equals(Crypto.digest(encrypted))) throw new IOException("Damaged attachment chunk.");
        return encrypted;
    }

    private byte[] readChunk(String id) throws IOException, GeneralSecurityException {
        return Crypto.content(session.key, context, loadChunk(id), false);
    }

    public InputStream image(Notes.Image image) {
        return new InputStream() {
            private final Iterator<JsonElement> ids = image.chunks.iterator();
            private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);

            private boolean ready() throws IOException {
                while (current.available() == 0 && ids.hasNext()) {
                    try {
                        current = new ByteArrayInputStream(readChunk(ids.next().getAsString()));
                    } catch (GeneralSecurityException error) {
                        throw new IOException("Image could not be authenticated.", error);
                    }
                }
                return current.available() != 0;
            }

            @Override public int read() throws IOException { return ready() ? current.read() : -1; }

            @Override public int read(byte[] data, int offset, int length) throws IOException {
                if (length == 0) return 0;
                return ready() ? current.read(data, offset, length) : -1;
            }
        };
    }

    private String stageChunk(byte[] plain) throws GeneralSecurityException, IOException {
        byte[] encrypted = Crypto.content(session.key, context, plain, true);
        String id = Crypto.digest(encrypted);
        disk.write(chunkPath(id), encrypted);
        return id;
    }

    private JsonArray stageImage(Notes.NewImage image) throws Exception {
        JsonArray ids = new JsonArray();
        try (InputStream stream = image.source.open()) {
            if (stream == null) throw new IOException("The selected image could not be opened.");
            byte[] chunk = new byte[CHUNK];
            int used = 0;
            int count;
            while ((count = stream.read(chunk, used, chunk.length - used)) != -1) {
                used += count;
                if (used == chunk.length) {
                    ids.add(stageChunk(chunk));
                    used = 0;
                }
            }
            if (used != 0) ids.add(stageChunk(Arrays.copyOf(chunk, used)));
        }
        if (ids.isEmpty()) throw new IOException("The selected image is empty.");
        return ids;
    }

    /** Saves the editor's fields as a new note ({@code prior == null}) or a new version of {@code prior}. */
    public synchronized Notes.Note save(Notes.Note prior, Notes.Fields fields) throws Exception {
        Notes.validate(fields);
        return store(prior, fields);
    }

    /** Quick add: a new note holding only {@code text} and the picked images. */
    public synchronized Notes.Note capture(String text, List<Notes.NewImage> images) throws Exception {
        if (text.trim().isEmpty()) throw new IllegalArgumentException("Write something before saving.");
        return store(null, Notes.Fields.capture(text, images));
    }

    /** Tags the note no-queue, leaving everything else untouched (even if the note has no content). */
    public synchronized Notes.Note excludeFromQueue(Notes.Note note) throws Exception {
        Notes.Fields fields = Notes.Fields.of(note);
        fields.noQueue = true;
        return store(note, fields);
    }

    public synchronized List<Links.Target> linkTargets() {
        return Links.project(state.base, Notes.project(state.base));
    }

    private Notes.Note store(Notes.Note prior, Notes.Fields fields) throws Exception {
        requireCurrent(prior);
        String key = prior == null ? null : prior.key;
        JsonObject value;
        if (key == null) {
            String id = UUID.randomUUID().toString();
            key = Json.recordKey("note", id);
            value = Json.object("kind", "note", "id", id);
        } else {
            value = prior.value.deepCopy();
        }
        Notes.apply(value, prior, fields);
        JsonArray files = new JsonArray();
        JsonArray refs = new JsonArray();
        Set<String> checked = new HashSet<>();
        for (Notes.Image image : fields.images) {
            if (prior == null || !prior.images.contains(image)) throw new IllegalArgumentException("Unexpected image.");
            for (JsonElement chunk : image.chunks) {
                String id = chunk.getAsString();
                if (checked.add(id)) readChunk(id);
            }
            refs.add(image.reference);
            files.add(image.chunks);
        }
        for (Notes.NewImage image : fields.added) {
            refs.add(Json.object("id", UUID.randomUUID().toString(), "mediaType", image.mediaType));
            files.add(stageImage(image));
        }
        if (!refs.isEmpty() || prior != null && !prior.images.isEmpty()) value.add("attachments", refs);
        if (prior != null && value.equals(prior.value) && files.equals(prior.files)) return prior;

        JsonObject head = head(key, value, files, false);
        if (prior != null && prior.observed.size() == 1) {
            SortedSet<String> changed = new TreeSet<>(value.keySet());
            changed.addAll(prior.value.keySet());
            changed.removeIf(field -> Objects.equals(value.get(field), prior.value.get(field)));
            head.add("edit", Json.object("from", prior.observed.get(0), "fields", Json.array(changed),
                    "files", !files.equals(prior.files)));
        }
        write(head, prior == null ? Collections.emptyList() : prior.observed);
        return new Notes.Note(key, value, files, Collections.singletonList(head.get("id").getAsString()), false);
    }

    public synchronized void delete(Notes.Note prior) throws IOException {
        requireCurrent(prior);
        write(head(prior.key, JsonNull.INSTANCE, new JsonArray(), true), prior.observed);
    }

    private void requireCurrent(Notes.Note prior) {
        if (state.owner != null) throw new IllegalStateException("This local workspace was moved to an account.");
        if (prior != null && !state.base.heads.keySet().containsAll(prior.observed))
            throw new IllegalStateException("This note changed on another device. Reopen it before editing.");
    }

    private static JsonObject head(String key, JsonElement value, JsonArray files, boolean deleted) {
        return Json.object("key", key, "id", UUID.randomUUID().toString(), "value", value, "files", files,
                "deleted", deleted);
    }

    private void write(JsonObject head, Collection<String> observed) throws IOException {
        commit(new State(state.base.append(head, observed), state.revision, true, null));
        status = session.token == null ? "Local workspace" : "Pending sync";
    }

    synchronized boolean adoptable() {
        return !state.base.heads.isEmpty() || state.owner != null;
    }

    // Sign-in serializes adoption with other account operations. The guest binds first so a
    // process interruption cannot copy the same local workspace into another account.
    synchronized void adoptBy(Replica destination) throws Exception {
        String owner = destination.session.scope();
        if (!adoptable()) return;
        if (state.owner != null && !owner.equals(state.owner))
            throw new IllegalStateException("Finish moving these local notes using their original account.");
        if (state.owner == null) commit(new State(state.base, state.revision, state.pending, owner));

        synchronized (destination) {
            State existing = destination.state;
            JsonArray heads = new JsonArray();
            Map<String, String> converted = new HashMap<>();
            for (Map.Entry<String, JsonObject> item : state.base.heads.entrySet()) {
                String id = item.getKey();
                if (existing.base.heads.containsKey(id) || existing.base.removed.contains(id)) continue;
                JsonObject head = item.getValue().deepCopy();
                for (JsonElement file : head.getAsJsonArray("files")) {
                    JsonArray chunks = file.getAsJsonArray();
                    for (int i = 0; i < chunks.size(); i++) {
                        String old = chunks.get(i).getAsString();
                        String next = converted.get(old);
                        if (next == null) {
                            next = destination.stageChunk(readChunk(old));
                            converted.put(old, next);
                        }
                        chunks.set(i, new JsonPrimitive(next));
                    }
                }
                heads.add(head);
            }
            Manifest adopted = Manifest.read(Json.object("heads", heads, "removed", Json.array(state.base.removed)));
            Manifest merged = existing.base.merge(adopted);
            if (!merged.equals(existing.base))
                destination.commit(new State(merged, existing.revision, true, null));
        }
    }

    private byte[] manifestContext(long revision) {
        return Json.bytes(Json.tuple("olymp", 1, session.id, "manifest", revision));
    }

    private static final class LocalChanged extends Exception {}

    public synchronized boolean pending() { return state.pending; }

    public void sync(Olymp.Transport transport) throws Exception {
        if (session.token == null) return;
        status = "Syncing";
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    syncRound(transport);
                    rejected = false;
                    return;
                } catch (LocalChanged retry) {
                    if (attempt == 4) {
                        status = "Pending sync";
                        return;
                    }
                } catch (Olymp.HttpFailure failure) {
                    if (failure.status != 409 || attempt == 4) throw failure;
                }
            }
        } catch (Olymp.HttpFailure error) {
            switch (error.status) {
                case 401:
                    rejected = true;
                    status = "Signed out by Olymp";
                    break;
                case 413: status = "Storage limit reached"; break;
                default: status = "Sync incomplete"; break;
            }
            throw error;
        } catch (Exception error) {
            status = error instanceof IOException ? "Connection or storage unavailable" : "Sync incomplete";
            throw error;
        }
    }

    private void syncRound(Olymp.Transport transport) throws Exception {
        State snapshot;
        synchronized (this) { snapshot = state; }
        JsonObject remoteState = Olymp.json(transport, session, "base", "GET", null).getAsJsonObject();
        long revision = remoteState.get("revision").getAsLong();
        JsonElement envelope = remoteState.get("envelope");
        Manifest remote;
        if (revision == 0 && envelope.isJsonNull()) remote = new Manifest();
        else remote = Manifest.read(Json.parse(Crypto.content(session.key, manifestContext(revision),
                Crypto.decode(envelope.getAsString()), false)).getAsJsonObject());
        Manifest combined = snapshot.base.merge(remote);

        Set<String> references = combined.references();
        Set<String> missing = new HashSet<>();
        // Batching keeps response bodies and per-request memory bounded.
        List<String> ordered = new ArrayList<>(references);
        for (int offset = 0; offset < ordered.size(); offset += 512) {
            List<String> batch = ordered.subList(offset, Math.min(offset + 512, ordered.size()));
            for (JsonElement ref : Olymp.json(transport, session, "missing", "POST", Json.array(batch)).getAsJsonArray()) {
                String id = ref.getAsString();
                if (!batch.contains(id)) throw new IOException("Invalid Olymp missing-chunk response.");
                missing.add(id);
            }
        }
        for (String id : references) {
            byte[] encrypted;
            boolean fetched = false;
            try {
                encrypted = loadChunk(id);
            } catch (IOException damagedOrAbsent) {
                encrypted = Olymp.request(transport, session, "chunks/" + id, "GET", null, null);
                if (!id.equals(Crypto.digest(encrypted))) throw new IOException("Invalid Olymp chunk digest.");
                fetched = true;
            }
            Crypto.content(session.key, context, encrypted, false);
            if (fetched) disk.write(chunkPath(id), encrypted);
            if (missing.contains(id))
                Olymp.request(transport, session, "chunks/" + id, "PUT", encrypted, "application/octet-stream");
        }
        synchronized (this) { if (state != snapshot) throw new LocalChanged(); }
        if (!missing.isEmpty() || !combined.equals(remote)) {
            long next = revision + 1;
            byte[] encrypted = Crypto.content(session.key, manifestContext(next), Json.bytes(combined.json()), true);
            long accepted = Olymp.json(transport, session, "base", "PUT",
                    Json.object("revision", revision, "envelope", Crypto.encode(encrypted),
                            "chunks", Json.array(ordered))).getAsLong();
            if (accepted != next) throw new IOException("Invalid Olymp revision acknowledgement.");
            revision = accepted;
        }
        State accepted;
        synchronized (this) {
            if (state != snapshot) throw new LocalChanged();
            if (snapshot.pending || snapshot.revision != revision || !snapshot.base.equals(combined)
                    || !Files.exists(directory.resolve("replica.json")))
                commit(new State(combined, revision, false, null));
            accepted = state;
        }
        Olymp.json(transport, session, "confirm", "POST", Json.object("revision", revision));
        synchronized (this) {
            status = state == accepted ? "Fully synced at revision " + revision : "Pending sync";
        }
    }
}
