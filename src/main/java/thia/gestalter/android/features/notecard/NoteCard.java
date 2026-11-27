package thia.gestalter.android.features.notecard;

import android.text.TextUtils;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import thia.gestalter.android.ui.*;
import thia.gestalter.core.Notes;

/** A compact summary of a note: headline, a few lines of text, and what else it carries. */
public final class NoteCard {
    private NoteCard() {}

    public static LinearLayout view(Views ui, Notes.Note note) {
        LinearLayout card = ui.column();
        card.setBackgroundColor(Theme.PANEL);
        card.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(6));
        String headline = Notes.headline(note);
        TextView title = ui.text(headline.isEmpty() ? "(empty)" : headline, 17, headline.isEmpty() ? Theme.MUTED : Theme.INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        ui.fill(card, title);
        String body = note.text.trim();
        if (note.title.trim().isEmpty()) {
            int newline = body.indexOf('\n');
            body = newline < 0 ? "" : body.substring(newline + 1).trim();
        }
        if (body.length() > 220) body = body.substring(0, 220) + "…";
        if (!body.isEmpty()) {
            TextView preview = ui.text(body, 15, Theme.INK);
            preview.setMaxLines(4);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            ui.fill(card, preview);
        }
        List<String> facts = new ArrayList<>();
        if (note.conflict) facts.add("Concurrent version");
        if (!note.images.isEmpty()) facts.add(note.images.size() == 1 ? "1 image" : note.images.size() + " images");
        for (String tag : note.tags) facts.add("#" + tag);
        if (note.noQueue) facts.add("Skips Queue");
        if (!facts.isEmpty()) ui.fill(card, ui.text(TextUtils.join(" · ", facts), 13, Theme.MUTED));
        return card;
    }
}
