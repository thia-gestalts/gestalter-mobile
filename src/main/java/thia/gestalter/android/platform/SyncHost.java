package thia.gestalter.android.platform;

import java.util.function.Consumer;

/** Implemented by the Application so OS callbacks can request a sync without knowing the workspace. */
public interface SyncHost {
    /** Runs one sync; {@code done} receives whether the job should be retried. */
    void requestSync(Consumer<Boolean> done);
}
