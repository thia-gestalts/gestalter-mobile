package thia.gestalter.core;

import java.util.*;
import java.util.regex.Pattern;

/** Lists are not records: every valid list-* tag on notes forms one, titled from the tag. */
public final class Lists {
    private static final String PREFIX = "list-";
    private static final Pattern TAG = Pattern.compile("^list-[a-z0-9]+(-[a-z0-9]+)*$");

    private Lists() {}

    public static final class ListOf {
        public final String tag;
        public final String title;
        /** One version per note key. */
        public final List<Notes.Note> notes;
        /** Some member has concurrent versions; editing waits until they are resolved. */
        public final boolean conflict;

        ListOf(String tag, List<Notes.Note> notes, boolean conflict) {
            this.tag = tag;
            this.title = title(tag);
            this.notes = Collections.unmodifiableList(notes);
            this.conflict = conflict;
        }
    }

    public static boolean isListTag(String tag) { return tag != null && TAG.matcher(tag).matches(); }

    /** list-grocery-items → Grocery Items */
    public static String title(String tag) {
        StringBuilder title = new StringBuilder();
        for (String word : tag.substring(PREFIX.length()).split("-")) {
            if (title.length() > 0) title.append(' ');
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return title.toString();
    }

    /** Grocery items! → list-grocery-items */
    public static String tag(String title) {
        String slug = (title == null ? "" : title).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) throw new IllegalArgumentException("Give the list a title.");
        return PREFIX + slug;
    }

    /** The label a list shows for a note: its title, else its headline. */
    public static String label(Notes.Note note) {
        return note.title.trim().isEmpty() ? Notes.headline(note) : note.title;
    }

    public static List<ListOf> project(List<Notes.Note> notes) {
        Map<String, Map<String, Notes.Note>> members = new TreeMap<>();
        Set<String> conflicted = new HashSet<>();
        for (Notes.Note note : notes)
            for (String tag : new LinkedHashSet<>(note.tags)) {
                if (!isListTag(tag)) continue;
                members.computeIfAbsent(tag, ignored -> new LinkedHashMap<>()).putIfAbsent(note.key, note);
                if (note.conflict) conflicted.add(tag);
            }
        List<ListOf> lists = new ArrayList<>();
        for (Map.Entry<String, Map<String, Notes.Note>> entry : members.entrySet()) {
            List<Notes.Note> sorted = new ArrayList<>(entry.getValue().values());
            sorted.sort(Comparator.comparing((Notes.Note note) -> label(note).toLowerCase(Locale.ROOT))
                    .thenComparing(note -> note.key));
            lists.add(new ListOf(entry.getKey(), sorted, conflicted.contains(entry.getKey())));
        }
        lists.sort(Comparator.comparing((ListOf list) -> list.title.toLowerCase(Locale.ROOT)).thenComparing(list -> list.tag));
        return lists;
    }

    /**
     * Up to three member keys: those in {@code keep} that still belong first, then random others,
     * preferring keys outside {@code avoid} so a shuffle shows new notes whenever enough exist.
     */
    public static List<String> sample(ListOf list, Random random, Collection<String> keep, Set<String> avoid) {
        List<String> keys = new ArrayList<>();
        for (Notes.Note note : list.notes) keys.add(note.key);
        List<String> chosen = new ArrayList<>();
        for (String key : keep) if (chosen.size() < 3 && keys.contains(key) && !chosen.contains(key)) chosen.add(key);
        keys.removeAll(chosen);
        Collections.shuffle(keys, random);
        keys.sort(Comparator.comparing(avoid::contains));
        for (String key : keys) if (chosen.size() < 3) chosen.add(key);
        return chosen;
    }
}
