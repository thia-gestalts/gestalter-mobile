package thia.gestalter.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for the note model: v3 fields, capture shape, edit diffs, links, search. */
public final class NotesTest {
    private static int checks;

    private static void require(boolean okay, String failure) {
        if (!okay) throw new AssertionError(failure);
        checks++;
    }

    private static void fails(String expected, ThrowingRunnable work) {
        try {
            work.run();
        } catch (Exception error) {
            require(error.getMessage() != null && error.getMessage().contains(expected),
                    "Expected failure '" + expected + "' but got: " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected failure: " + expected);
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    private static Replica replica(Path directory) throws Exception {
        DurableFiles disk = new DurableFiles(path -> {});
        Olymp.Session guest = new Olymp.Session("local:", UUID.randomUUID().toString(), "Local workspace", null,
                Crypto.random(32));
        return new Replica(disk, directory, guest);
    }

    private static Notes.Note only(Replica replica, String key) {
        List<Notes.Note> versions = new ArrayList<>();
        for (Notes.Note note : replica.notes()) if (note.key.equals(key)) versions.add(note);
        require(versions.size() == 1, "Expected one version of " + key + ", got " + versions.size());
        return versions.get(0);
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("gestalter-notes-test");
        Replica replica = replica(temp);

        // Capture writes only text (and attachments when picked).
        Notes.Note captured = replica.capture("# Heading\nbody", Collections.emptyList());
        require(captured.value.keySet().equals(new HashSet<>(Arrays.asList("kind", "id", "text"))),
                "Capture wrote more than text: " + captured.value);
        require(Notes.headline(captured).equals("Heading"), "Headline must strip Markdown prefixes");
        fails("Write something", () -> replica.capture("   ", Collections.emptyList()));

        // Full editor: every v3 field round-trips; no-queue lives in tags but is not a user tag.
        Notes.Fields fields = Notes.Fields.of(captured);
        fields.title = "Curves";
        fields.extra = "Revisit endpoints.";
        require(Notes.Fields.addUnique(fields.aliases, "  Interpolation  "), "Alias was not added");
        require(!Notes.Fields.addUnique(fields.aliases, "Interpolation"), "Duplicate alias was added");
        require(!Notes.Fields.addUnique(fields.aliases, " "), "Blank alias was added");
        fields.tags.add("geometry");
        fields.noQueue = true;
        Notes.Note full = replica.save(captured, fields);
        require(full.title.equals("Curves") && full.extra.equals("Revisit endpoints.")
                && full.aliases.equals(Collections.singletonList("Interpolation"))
                && full.tags.equals(Collections.singletonList("geometry")) && full.noQueue, "Fields did not round-trip");
        require(Json.list(full.value, "tags").equals(Json.array(Arrays.asList("geometry", "no-queue"))),
                "Stored tags must append no-queue: " + full.value);
        require(Notes.Fields.of(full).equals(fields), "Fields.of must reproduce the saved draft");
        require(Notes.headline(full).equals("Curves"), "Title wins the headline");

        // Edit metadata lists exactly the changed fields.
        Notes.Fields retitle = Notes.Fields.of(full);
        retitle.title = "Curves II";
        replica.save(full, retitle);
        Notes.Note retitled = only(replica, full.key);
        require(retitled.title.equals("Curves II") && retitled.tags.equals(full.tags), "Retitle lost fields");
        require(replica.save(retitled, Notes.Fields.of(retitled)) == retitled, "An unchanged save must be a no-op");

        // Links: relation labels, reserved placement links, self/duplicate protection.
        Notes.Note other = replica.save(null, titled("Endpoints"));
        List<Links.Target> targets = replica.linkTargets();
        require(Links.label(targets, "note", other.id).equals("Endpoints"), "Missing note link target");
        List<Links.Target> candidates = Links.candidates(targets, "end", retitled.id, retitled.related, 20);
        require(candidates.size() == 1 && candidates.get(0).id.equals(other.id), "Link candidates wrong");
        require(Links.candidates(targets, "curves", retitled.id, retitled.related, 20).isEmpty(),
                "A note must not be offered as a link to itself");
        Notes.Fields linking = Notes.Fields.of(retitled);
        linking.related.add(new Notes.Relation("note", other.id, " related "));
        linking.related.add(new Notes.Relation("note", other.id + "-place", "follows"));
        Notes.Note linked = replica.save(retitled, linking);
        require(linked.related.get(0).relation.equals("related"), "Relation label must be trimmed");
        require(linked.related.get(1).reserved(), "Placement link must be recognised as reserved");
        require(Links.candidates(replica.linkTargets(), "end", linked.id, linked.related, 20).isEmpty(),
                "Linked records must not be offered again");
        Notes.Fields twice = Notes.Fields.of(linked);
        twice.related.add(new Notes.Relation("note", other.id, null));
        fails("linked twice", () -> replica.save(linked, twice));
        Notes.Fields unlabel = Notes.Fields.of(linked);
        unlabel.related.set(0, unlabel.related.get(0).withRelation(""));
        Notes.Note unlabelled = replica.save(linked, unlabel);
        require(!unlabelled.value.getAsJsonArray("related").get(0).getAsJsonObject().has("relation"),
                "Clearing a relation must drop the label");

        // Validation follows note/v3's anyOf and the tag rules.
        fails("Add some content", () -> replica.save(null, new Notes.Fields()));
        Notes.Fields reserved = titled("x");
        reserved.tags.add("no-queue");
        fails("Skip Queue", () -> replica.save(null, reserved));
        Notes.Fields blank = titled("x");
        blank.aliases.add(" ");
        fails("empty aliases", () -> replica.save(null, blank));
        Notes.Fields titleOnly = titled("Only a title");
        Notes.Note titleNote = replica.save(null, titleOnly);
        require(!titleNote.value.has("text"), "A title-only note must not invent text");

        // Clearing a field that existed keeps it as an empty string (desktop parity).
        Notes.Fields cleared = Notes.Fields.of(unlabelled);
        cleared.extra = "";
        cleared.aliases.clear();
        Notes.Note emptied = replica.save(unlabelled, cleared);
        require(emptied.value.get("extra").getAsString().isEmpty()
                && emptied.value.getAsJsonArray("aliases").size() == 0, "Cleared fields must stay present");

        // Search: title, text and aliases; ordered by headline; empty query lists everything.
        Notes.Fields aliased = titled("Zebra");
        aliased.aliases.add("Striped horse");
        replica.save(null, aliased);
        List<String> all = new ArrayList<>();
        for (Notes.Note note : Notes.search(replica.notes(), "")) all.add(Notes.headline(note));
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        require(all.equals(sorted), "Search must order by headline: " + all);
        require(Notes.search(replica.notes(), "STRIPED").size() == 1, "Alias search is case-insensitive");
        require(Notes.search(replica.notes(), "body").size() == 1, "Text search failed");

        // Find reopens the same version and falls back to any version of the key.
        require(Notes.find(replica.notes(), emptied.key, emptied.version).version.equals(emptied.version),
                "Find lost the version");
        require(Notes.find(replica.notes(), emptied.key, "gone") != null, "Find must fall back to the key");

        // Queue exclusion keeps all fields.
        Notes.Note excluded = replica.excludeFromQueue(titleNote);
        require(excluded.noQueue && excluded.title.equals("Only a title"), "Exclusion changed the note");

        // Delete.
        replica.delete(excluded);
        require(replica.notes().stream().noneMatch(note -> note.key.equals(excluded.key)), "Delete failed");

        // The v3 example record projects with every field.
        JsonArray examples = Json.parse(Files.readAllBytes(Paths.get("vendor/thia-gestalts/examples/v3/records.json")))
                .getAsJsonArray();
        JsonObject example = examples.get(0).getAsJsonObject();
        Notes.Note projected = new Notes.Note(Json.recordKey("note", example.get("id").getAsString()), example,
                new JsonArray(), Collections.singletonList("example"), false);
        require(projected.title.equals("A note about curves") && projected.aliases.size() == 1
                && projected.related.get(0).relation.equals("related"), "v3 example did not project");

        System.out.println("NotesTest: " + checks + " checks passed.");
    }

    private static Notes.Fields titled(String title) {
        Notes.Fields fields = new Notes.Fields();
        fields.title = title;
        return fields;
    }
}
