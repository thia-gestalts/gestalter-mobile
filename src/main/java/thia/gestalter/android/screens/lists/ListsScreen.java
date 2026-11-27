package thia.gestalter.android.screens.lists;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.util.*;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.Lists;
import thia.gestalter.core.Notes;

/** Every list (a list-* tag on notes) with three random members, a shuffle and an edit form. */
public final class ListsScreen extends Screen {
    private final Random random = new Random();
    /** Shown note keys per list tag. */
    private Map<String, ArrayList<String>> shown = new HashMap<>();
    private LinearLayout content;
    private int request;

    public ListsScreen(ScreenHost host, Bundle state) {
        super(host);
        Bundle saved = state == null ? null : state.getBundle("shown");
        if (saved != null) for (String tag : saved.keySet()) shown.put(tag, saved.getStringArrayList(tag));
    }

    @Override public Route route() { return Route.tab(Route.Kind.LISTS); }

    @Override public String title() { return "Lists"; }

    /** Opening the tab anew draws fresh samples; returning from a note keeps the shown ones. */
    @Override public void entered() { shown = new HashMap<>(); }

    @Override public void build(LinearLayout page, LinearLayout actions) {
        content = host.views().column();
        host.views().fill(page, content);
        load();
    }

    @Override public void changed() { load(); }

    @Override public void save(Bundle state) {
        Bundle saved = new Bundle();
        for (Map.Entry<String, ArrayList<String>> entry : shown.entrySet())
            saved.putStringArrayList(entry.getKey(), entry.getValue());
        state.putBundle("shown", saved);
    }

    private void load() {
        int current = ++request;
        LinearLayout target = content;
        if (target == null) return;
        Views ui = host.views();
        Workspace workspace = host.workspace();
        if (!workspace.ready()) {
            if (target.getChildCount() == 0) ui.fill(target, ui.text("Opening Lists…", 16, Theme.MUTED));
            return;
        }
        workspace.lists(lists -> {
            if (current == request && target == content) show(lists);
        }, error -> host.message(Workspace.describe(error, false), true));
    }

    private void show(List<Lists.ListOf> lists) {
        Views ui = host.views();
        content.removeAllViews();
        if (lists.isEmpty())
            ui.margin(content, ui.text("No lists yet. Tag notes with list- and a name, e.g. list-books-to-read.",
                    16, Theme.MUTED), 12);
        Map<String, ArrayList<String>> next = new HashMap<>();
        for (Lists.ListOf list : lists) {
            List<String> keep = shown.getOrDefault(list.tag, new ArrayList<>());
            next.put(list.tag, new ArrayList<>(Lists.sample(list, random, keep, Collections.emptySet())));
        }
        shown = next;
        for (Lists.ListOf list : lists) ui.margin(content, section(list), 16);
    }

    private LinearLayout section(Lists.ListOf list) {
        Views ui = host.views();
        LinearLayout section = ui.column();
        LinearLayout header = ui.row();
        ui.weighted(header, ui.heading(list.title, 20));
        header.addView(ui.text(String.valueOf(list.notes.size()), 14, Theme.MUTED));
        ImageButton shuffle = ui.icon(Icons.SHUFFLE, "Shuffle " + list.title, () -> {
            shown.put(list.tag, new ArrayList<>(Lists.sample(list, random, Collections.emptyList(),
                    new HashSet<>(shown.getOrDefault(list.tag, new ArrayList<>())))));
            load();
        });
        shuffle.setEnabled(list.notes.size() > 3);
        shuffle.setAlpha(list.notes.size() > 3 ? 1f : 0.35f);
        header.addView(shuffle, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        header.addView(ui.icon(Icons.EDIT, "Edit " + list.title, () -> ListEditor.open(host, list, this::load)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        ui.fill(section, header);
        for (String key : shown.get(list.tag))
            for (Notes.Note note : list.notes)
                if (note.key.equals(key)) {
                    String label = Lists.label(note);
                    Button item = ui.button(label.isEmpty() ? "(empty)" : label, false, () -> host.go(Route.note(note)));
                    item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    item.setContentDescription("Open note " + label);
                    ui.margin(section, item, 4);
                }
        return section;
    }
}
