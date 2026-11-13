package uk.ac.cam.cares.jps.ui.impl.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

// NEW FILE
/**
 * Holds the map display options chosen on the Display Options screen (DisplayOptionsFragment, feature:user).
 *
 * It lives in core:ui-impl, next to AppPreferenceViewModel, because both feature:user (the settings screen
 * that writes the choice) and feature:timeline (TrajectoryManager, which repaints the map when the choice
 * changes) depend on that module. Both fetch it with ViewModelProvider(requireActivity()). Being activity scoped, the choice also survives the dialog closing and the timeline fragment's
 * view being recreated.
 */
@HiltViewModel
public class DisplayOptionsViewModel extends ViewModel {

    /** How the recorded trajectory is coloured on the map. */
    public enum TrajectoryView { TRIPS_AND_VISITS, ACTIVITY }

    private final MutableLiveData<TrajectoryView> _trajectoryView = new MutableLiveData<>(TrajectoryView.TRIPS_AND_VISITS);
    public final LiveData<TrajectoryView> trajectoryView = _trajectoryView;

    @Inject
    public DisplayOptionsViewModel() {
    }

    public void setTrajectoryView(TrajectoryView view) {
        // setValue ignores nothing, but observers only need to react to real changes, so skip no-ops here
        if (view != _trajectoryView.getValue()) {
            _trajectoryView.setValue(view);
        }
    }

    public TrajectoryView getTrajectoryView() {
        return _trajectoryView.getValue();
    }
}
