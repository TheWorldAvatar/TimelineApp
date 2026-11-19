package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.time.LocalDate;

import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.TripAgentViewModel;

/**
 * Runs the trip agent followed by the exposure calculation every {@link #INTERVAL_MS} while the
 * timeline screen is visible. Does nothing while the screen is stopped.
 * <p>
 * - Only runs while the selected date is today, since older days no longer receive new data.
 * - {@link TripAgentViewModel#runTripAgent} ignores a call while a previous run is still in progress,
 * so slow runs never overlap with each other or with the manual button.
 * - When the screen becomes visible again the next run waits until a full interval has passed since
 * the last one, so switching between screens does not trigger a run every time.
 */
public class AutoTripRunner implements DefaultLifecycleObserver {

    public static final long INTERVAL_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TripAgentViewModel tripAgentViewModel;
    private final NormalBottomSheetViewModel bottomSheetViewModel;
    private final Runnable tick = this::onTick;
    private long lastRunMs = -INTERVAL_MS; // so the first start runs immediately

    public AutoTripRunner(@NonNull LifecycleOwner owner,
                          @NonNull TripAgentViewModel tripAgentViewModel,
                          @NonNull NormalBottomSheetViewModel bottomSheetViewModel) {
        this.tripAgentViewModel = tripAgentViewModel;
        this.bottomSheetViewModel = bottomSheetViewModel;
        owner.getLifecycle().addObserver(this);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        long wait = Math.max(0, lastRunMs + INTERVAL_MS - SystemClock.elapsedRealtime());
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, wait);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        handler.removeCallbacks(tick);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        handler.removeCallbacks(tick);
    }

    private void onTick() {
        LocalDate selected = bottomSheetViewModel.selectedDate.getValue();
        if (LocalDate.now().equals(selected)) {
            lastRunMs = SystemClock.elapsedRealtime();
            tripAgentViewModel.runTripAgent(selected);
        }
        handler.postDelayed(tick, INTERVAL_MS);
    }
}
