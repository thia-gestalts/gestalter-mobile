package thia.gestalter.android.navigation;

import android.app.Activity;
import thia.gestalter.android.ui.Views;
import thia.gestalter.android.workspace.Workspace;

/** What the shell offers the screen it currently shows. */
public interface ScreenHost {
    Activity activity();

    Views views();

    Workspace workspace();

    /** Switches tab, or pushes a note editor over the current screen. Asks the current screen first. */
    void go(Route route);

    /** Removes this pushed screen without asking it, e.g. after its note was deleted. */
    void close();

    /** Rebuilds the current screen's views from its state. */
    void render();

    /** Shows a short status line under the page; an empty message hides it. */
    void message(String text, boolean error);

    void hideKeyboard();
}
