package uk.ac.cam.cares.jps.timeline.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

// NEW FILE
/**
 * Holds the map display options chosen in the profile dialog (UserDialogFragment).
 *
 * It is fetched with ViewModelProvider(requireActivity()) by both the dialog (which writes the choice)
 * and TrajectoryManager (which repaints the map when the choice changes), the same way RoutesViewModel is
 * shared. Being activity scoped, the choice also survives the dialog closing and the timeline fragment's
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
