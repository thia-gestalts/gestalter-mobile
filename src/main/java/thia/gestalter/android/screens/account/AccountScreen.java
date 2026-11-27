package thia.gestalter.android.screens.account;

import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.*;

/** Olymp sign-in, sign-out and session renewal. The origin is fixed by the build. */
public final class AccountScreen extends Screen {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
    private static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private Profile.Auth shown;
    private boolean busy;
    private LinearLayout page;
    private LinearLayout actions;

    public AccountScreen(ScreenHost host, Bundle state) { super(host); }

    @Override public Route route() { return Route.tab(Route.Kind.ACCOUNT); }

    @Override public String title() { return "Olymp account"; }

    @Override public boolean busy() { return busy; }

    @Override public void changed() {
        Profile profile = host.workspace().profile();
        if (!busy && profile != null && profile.auth() != shown) host.render();
    }

    @Override public void build(LinearLayout page, LinearLayout actions) {
        this.page = page;
        this.actions = actions;
        Views ui = host.views();
        Workspace workspace = host.workspace();
        Profile profile = workspace.profile();
        shown = profile == null ? null : profile.auth();
        if (profile == null) {
            ui.fill(page, ui.text(workspace.status(), 16, Theme.MUTED));
            return;
        }
        String origin = workspace.origin();
        Olymp.Session active = profile.account();
        if (active == null) {
            ui.fill(page, ui.text("Sign in to sync notes with " + origin + ".", 14, Theme.MUTED));
            EditText name = ui.field(page, "Login name", TEXT, "");
            EditText password = ui.field(page, "Password", PASSWORD, "");
            CheckBox include = new CheckBox(ui.context);
            include.setText("Include notes from this local workspace");
            include.setChecked(true);
            ui.margin(page, include, 9);
            ui.fill(actions, ui.button("Sign in", true, () -> authenticate(origin,
                    name.getText().toString(), password, false, include.isChecked())));
            ui.fill(actions, ui.button("Create account", false, () -> authenticate(origin,
                    name.getText().toString(), password, true, include.isChecked())));
            return;
        }
        ui.fill(page, ui.text(active.name, 19, Theme.INK));
        ui.fill(page, ui.text(active.origin, 14, Theme.MUTED));
        boolean here = active.origin.equals(origin);
        if (!here) {
            ui.fill(page, ui.text("This version of Gestalter syncs with " + origin
                    + ". Let pending notes sync, then sign out and sign in again to move this account.", 14, Theme.MUTED));
        }
        if (shown == Profile.Auth.REJECTED) {
            ui.fill(page, ui.text("Olymp ended this session, so sync is paused. Notes on this device are kept.",
                    14, Theme.ERROR));
            if (here) {
                EditText password = ui.field(page, "Password", PASSWORD, "");
                ui.fill(actions, ui.button("Sign in again", true,
                        () -> authenticate(active.origin, active.name, password, false, false)));
            }
        } else if (here) {
            ui.fill(page, ui.text("Signed in. Notes sync automatically.", 14, Theme.MUTED));
        }
        ui.fill(actions, ui.button("Sign out", false, () -> {
            if (!start()) return;
            workspace.signOut(this::finished, this::failed);
        }));
    }

    private void authenticate(String origin, String name, EditText field, boolean signup, boolean includeLocal) {
        if (busy) return;
        byte[] password = Json.bytes(field.getText().toString());
        field.getText().clear();
        if (!start()) return;
        host.workspace().signIn(origin, name, password, signup, includeLocal, this::finished, this::failed);
    }

    private boolean start() {
        if (busy) return false;
        busy = true;
        host.hideKeyboard();
        Views.setEnabled(page, false);
        Views.setEnabled(actions, false);
        host.message("Working…", false);
        return true;
    }

    private void finished() {
        busy = false;
        host.render();
    }

    private void failed(Exception error) {
        busy = false;
        Views.setEnabled(page, true);
        Views.setEnabled(actions, true);
        host.message(Workspace.describe(error, true), true);
    }
}
