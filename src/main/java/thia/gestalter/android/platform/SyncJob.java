package thia.gestalter.android.platform;

import android.app.job.*;
import android.util.SparseArray;

public final class SyncJob extends JobService {
    private final SparseArray<JobParameters> active = new SparseArray<>();

    @Override public boolean onStartJob(JobParameters params) {
        int id = params.getJobId();
        active.put(id, params);
        ((SyncHost) getApplication()).requestSync(retry -> {
            if (active.get(id) == params) {
                active.remove(id);
                jobFinished(params, retry);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active.get(params.getJobId()) == params) active.remove(params.getJobId());
        return true;
    }
}
