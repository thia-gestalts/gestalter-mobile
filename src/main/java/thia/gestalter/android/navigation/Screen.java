package thia.gestalter.android.navigation;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

/**
 * One screen of the app. The shell owns the frame (header, scrolling page, actions, navigation);
 * a screen keeps its own state and fills {@code page} and {@code actions} on every render.
 */
public abstract class Screen {
    protected final ScreenHost host;

    protected Screen(ScreenHost host) { this.host = host; }

    public abstract Route route();

    public abstract String title();

    public abstract void build(LinearLayout page, LinearLayout actions);

    /** The person switched to this tab from another one (not called when a pushed screen closes). */
    public void entered() {}

    /** Something in the workspace changed (sync, account, a write elsewhere). */
    public void changed() {}

    /** While true, the shell ignores navigation. */
    public boolean busy() { return false; }

    /** Called before the shell leaves this screen; call {@code proceed} to allow it. */
    public void requestLeave(Runnable proceed) { proceed.run(); }

    public void result(int request, int resultCode, Intent data) {}

    public void save(Bundle state) {}
}
