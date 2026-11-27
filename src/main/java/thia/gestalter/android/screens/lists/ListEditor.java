package thia.gestalter.android.screens.lists;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.*;
import java.util.*;
import thia.gestalter.android.navigation.ScreenHost;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.Lists;
import thia.gestalter.core.Notes;

/** One form for the list title (its implied tag) and every member note's title. */
final class ListEditor {
    private ListEditor() {}

    static void open(ScreenHost host, Lists.ListOf list, Runnable saved) {
        Views ui = host.views();
        if (list.conflict) {
            host.message("Resolve conflicting versions in this list first.", true);
            return;
        }
        LinearLayout form = ui.column();
        form.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8));
        EditText title = ui.field(form, "List title",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, list.title);
        ui.margin(form, ui.text("Notes", 14, Theme.INK), 14);
        Map<String, EditText> titles = new LinkedHashMap<>();
        for (Notes.Note note : list.notes) {
            String headline = Notes.headline(note);
            EditText input = ui.editor(headline.isEmpty() ? "Title" : headline, note.title, false, 0);
            input.setContentDescription("Title of " + (headline.isEmpty() ? "untitled note" : headline));
            titles.put(note.key, input);
            ui.margin(form, input, 6);
        }
        TextView error = ui.text("", 14, Theme.ERROR);
        ui.margin(form, error, 8);
        ScrollView scroll = new ScrollView(ui.context);
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(host.activity()).setTitle("Edit list").setView(scroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(shown -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(view -> {
                Map<String, String> values = new LinkedHashMap<>();
                titles.forEach((key, input) -> values.put(key, input.getText().toString()));
                save.setEnabled(false);
                error.setText("");
                host.workspace().saveList(list.tag, title.getText().toString(), values, lists -> {
                    dialog.dismiss();
                    host.message("List saved.", false);
                    saved.run();
                }, failure -> {
                    save.setEnabled(true);
                    error.setText(Workspace.describe(failure, false));
                });
            });
        });
        dialog.show();
    }
}
