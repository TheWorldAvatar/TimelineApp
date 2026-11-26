package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;

import java.time.LocalDate;

import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.TripAgentViewModel;

/**
 * Runs the trip agent followed by the exposure calculation every {@link #INTERVAL_MS} while the
 * timeline screen is visible and no sensor recording is in progress.
 * <p>
 * - Only runs while the selected date is today, since older days no longer receive new data.
 * - {@link TripAgentViewModel#runTripAgent} ignores a call while a previous run is still in progress,
 * so slow runs never overlap with each other or with the manual button.
 * - Pauses automatic runs while sensor recording is active and resumes when recording stops,
 * still respecting {@link #INTERVAL_MS} since the last run.
 */
public class AutoTripRunner implements DefaultLifecycleObserver {

    public static final long INTERVAL_MS = 5 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TripAgentViewModel tripAgentViewModel;
    private final NormalBottomSheetViewModel bottomSheetViewModel;
    private final Runnable tick = this::onTick;
    private long lastRunMs = -INTERVAL_MS; // so the first start runs immediately
    private boolean isStarted = false;
    private boolean isRecording;

    public AutoTripRunner(@NonNull LifecycleOwner owner,
                          @NonNull TripAgentViewModel tripAgentViewModel,
                          @NonNull NormalBottomSheetViewModel bottomSheetViewModel,
                          @NonNull LiveData<Boolean> recordingState) {
        this.tripAgentViewModel = tripAgentViewModel;
        this.bottomSheetViewModel = bottomSheetViewModel;
        this.isRecording = Boolean.TRUE.equals(recordingState.getValue());
        owner.getLifecycle().addObserver(this);
        recordingState.observe(owner, recording -> {
            boolean nowRecording = Boolean.TRUE.equals(recording);
            if (nowRecording == isRecording) {
                return;
            }
            isRecording = nowRecording;
            if (isRecording) {
                handler.removeCallbacks(tick);
            } else if (isStarted) {
                scheduleNextTick();
            }
        });
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        isStarted = true;
        scheduleNextTick();
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        isStarted = false;
        handler.removeCallbacks(tick);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        isStarted = false;
        handler.removeCallbacks(tick);
    }

    private void scheduleNextTick() {
        handler.removeCallbacks(tick);
        if (!isStarted || isRecording) {
            return;
        }
        long wait = Math.max(0, lastRunMs + INTERVAL_MS - SystemClock.elapsedRealtime());
        handler.postDelayed(tick, wait);
    }

    private void onTick() {
        if (!isStarted || isRecording) {
            return;
        }
        LocalDate selected = bottomSheetViewModel.selectedDate.getValue();
        if (LocalDate.now().equals(selected)) {
            lastRunMs = SystemClock.elapsedRealtime();
            tripAgentViewModel.runTripAgent(selected);
        }
        if (isStarted && !isRecording) {
            handler.postDelayed(tick, INTERVAL_MS);
        }
    }
}