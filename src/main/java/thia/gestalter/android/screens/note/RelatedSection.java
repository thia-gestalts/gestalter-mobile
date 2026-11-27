package thia.gestalter.android.screens.note;

import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;
import thia.gestalter.android.ui.*;
import thia.gestalter.core.*;

/**
 * Links from the note to other records: each with an editable relation label and an unlink
 * button, plus a search that adds links. Zettelkasten placements are kept but not shown.
 */
final class RelatedSection {
    private final Views ui;
    private final NoteDraft draft;
    private final Runnable changed;
    private final Consumer<String> openNote;
    private List<Links.Target> targets = Collections.emptyList();
    private LinearLayout rows;
    private LinearLayout results;
    private EditText search;
    private String query = "";

    RelatedSection(Views ui, NoteDraft draft, Runnable changed, Consumer<String> openNote) {
        this.ui = ui;
        this.draft = draft;
        this.changed = changed;
        this.openNote = openNote;
    }

    View view() {
        LinearLayout box = ui.column();
        ui.fill(box, ui.heading("Related records", 16));
        rows = ui.column();
        ui.fill(box, rows);
        search = ui.editor("Link a note, source or topic", query, false, 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_FILTER);
        Views.onText(search, value -> {
            query = value;
            showResults();
        });
        ui.margin(box, search, 8);
        results = ui.column();
        ui.fill(box, results);
        showRows();
        showResults();
        return box;
    }

    /** Updates labels and search results; rebuilding is skipped when nothing changed, to keep focus. */
    void targets(List<Links.Target> loaded) {
        boolean same = loaded.size() == targets.size();
        for (int i = 0; same && i < loaded.size(); i++) {
            Links.Target a = loaded.get(i), b = targets.get(i);
            same = a.kind.equals(b.kind) && a.id.equals(b.id) && a.label.equals(b.label);
        }
        targets = loaded;
        if (same || rows == null) return;
        showRows();
        showResults();
    }

    private void showRows() {
        rows.removeAllViews();
        List<Notes.Relation> related = draft.fields.related;
        boolean any = false;
        for (Notes.Relation link : new ArrayList<>(related)) {
            if (link.reserved()) continue;
            any = true;
            String label = Links.label(targets, link.kind, link.id);
            LinearLayout row = ui.row();
            TextView name = ui.text(label == null ? "Unavailable · " + link.id : label + "  · " + link.kind, 15,
                    label == null ? Theme.MUTED : Theme.INK);
            if (label != null && link.kind.equals("note")) {
                name.setTextColor(Theme.ACCENT);
                name.setContentDescription("Open linked note " + label);
                name.setOnClickListener(view -> openNote.accept(Json.recordKey("note", link.id)));
            }
            name.setPadding(0, ui.dp(8), ui.dp(8), ui.dp(8));
            ui.weighted(row, name);
            EditText relation = ui.editor("Relation", link.relation == null ? "" : link.relation, false, 0);
            relation.setInputType(InputType.TYPE_CLASS_TEXT);
            relation.setContentDescription("Relationship to " + (label == null ? link.id : label));
            Views.onText(relation, value -> {
                int index = indexOf(link);
                if (index < 0) return;
                related.set(index, related.get(index).withRelation(value));
                changed.run();
            });
            row.addView(relation, new LinearLayout.LayoutParams(ui.dp(124), -2));
            row.addView(ui.button("Unlink", false, () -> {
                int index = indexOf(link);
                if (index >= 0) related.remove(index);
                showRows();
                showResults();
                changed.run();
            }));
            ui.margin(rows, row, 4);
        }
        if (!any) ui.fill(rows, ui.text("No linked records.", 14, Theme.MUTED));
    }

    private int indexOf(Notes.Relation link) {
        List<Notes.Relation> related = draft.fields.related;
        for (int i = 0; i < related.size(); i++)
            if (related.get(i).sameTarget(link.kind, link.id)) return i;
        return -1;
    }

    private void showResults() {
        results.removeAllViews();
        if (query.trim().isEmpty()) return;
        List<Links.Target> found = Links.candidates(targets, query, draft.noteId(), draft.fields.related, 20);
        if (found.isEmpty()) ui.fill(results, ui.text("No matching records.", 14, Theme.MUTED));
        for (Links.Target target : found) {
            Button add = ui.button(target.label + "  · " + target.kind, false, () -> {
                draft.fields.related.add(new Notes.Relation(target.kind, target.id, null));
                showRows();
                search.getText().clear();
                changed.run();
            });
            add.setContentDescription("Link " + target.label);
            ui.margin(results, add, 4);
        }
    }
}
