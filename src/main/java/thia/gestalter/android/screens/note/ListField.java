package thia.gestalter.android.screens.note;

import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.List;
import thia.gestalter.android.ui.*;
import thia.gestalter.core.Notes;

/** Aliases or tags: removable chips plus an input that adds a trimmed, unique entry. */
final class ListField {
    private final Views ui;
    private final String label;
    private final String singular;
    private final List<String> values;
    private final Runnable changed;
    private Flow chips;
    private EditText input;

    ListField(Views ui, String label, String singular, List<String> values, Runnable changed) {
        this.ui = ui;
        this.label = label;
        this.singular = singular;
        this.values = values;
        this.changed = changed;
    }

    View view() {
        LinearLayout box = ui.column();
        ui.fill(box, ui.text(label, 14, Theme.INK));
        chips = ui.flow();
        ui.margin(box, chips, 4);
        LinearLayout add = ui.row();
        input = ui.editor("Add " + singular, "", false, 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        Views.onSubmit(input, this::submit);
        ui.weighted(add, input);
        add.addView(ui.button("Add", false, this::submit));
        ui.margin(box, add, 4);
        refresh();
        return box;
    }

    private void submit() {
        String entry = input.getText().toString();
        if (!Notes.Fields.addUnique(values, entry)) {
            if (!entry.trim().isEmpty()) input.getText().clear();
            return;
        }
        input.getText().clear();
        refresh();
        changed.run();
    }

    private void refresh() {
        chips.removeAllViews();
        for (String value : values) {
            chips.addView(ui.chip(value, () -> {
                values.remove(value);
                refresh();
                changed.run();
            }));
        }
        chips.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
