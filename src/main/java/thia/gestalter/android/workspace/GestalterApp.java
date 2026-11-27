package thia.gestalter.android.workspace;

import android.app.Application;
import android.content.Context;
import java.util.function.Consumer;
import thia.gestalter.android.platform.SyncHost;

public final class GestalterApp extends Application implements SyncHost {
    private Workspace workspace;

    @Override public void onCreate() {
        super.onCreate();
        workspace = new Workspace(this);
    }

    public static Workspace workspace(Context context) {
        return ((GestalterApp) context.getApplicationContext()).workspace;
    }

    @Override public void requestSync(Consumer<Boolean> done) { workspace.requestSync(done); }
}
