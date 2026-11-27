package thia.gestalter.android.platform;

import android.app.job.*;
import android.content.*;
import android.net.*;

public final class SyncSignals {
    private static final int RECONNECT = 11;
    private static final int PERIODIC = 12;
    private final JobScheduler scheduler;
    private final ComponentName service;

    public SyncSignals(Context context, Runnable online) {
        scheduler = context.getSystemService(JobScheduler.class);
        service = new ComponentName(context, SyncJob.class);
        context.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { online.run(); }
                });
    }

    public void schedule(boolean pending) {
        if (!current(PERIODIC))
            scheduler.schedule(job(PERIODIC).setPeriodic(15 * 60 * 1000L).build());
        if (pending && !current(RECONNECT))
            scheduler.schedule(job(RECONNECT).setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }

    public void cancel() {
        scheduler.cancel(RECONNECT);
        scheduler.cancel(PERIODIC);
    }

    /** Persisted jobs from an older build may still name a service class that no longer exists. */
    private boolean current(int id) {
        JobInfo pending = scheduler.getPendingJob(id);
        return pending != null && service.equals(pending.getService());
    }

    private JobInfo.Builder job(int id) {
        return new JobInfo.Builder(id, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);
    }
}
