package uk.ac.cam.cares.jps.ui.impl.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Holds the map display and advanced options chosen on the Advanced Options screen (DisplayOptionsFragment, feature:user).
 *
 * It lives in core:ui-impl, next to AppPreferenceViewModel, because both feature:user (the settings screen
 * that writes choices/actions) and feature:timeline (TrajectoryManager and DatasetLayerManager, which react
 * to them) depend on that module. Both fetch it with ViewModelProvider(requireActivity()).
 */
@HiltViewModel
public class DisplayOptionsViewModel extends ViewModel {

    /** How the recorded trajectory is coloured on the map. */
    public enum TrajectoryView { TRIPS_AND_VISITS, ACTIVITY }

    private final MutableLiveData<TrajectoryView> _trajectoryView = new MutableLiveData<>(TrajectoryView.ACTIVITY);
    public final LiveData<TrajectoryView> trajectoryView = _trajectoryView;

    private final MutableLiveData<Boolean> _showDatasetLayer = new MutableLiveData<>(false);
    public final LiveData<Boolean> showDatasetLayer = _showDatasetLayer;

    private final MutableLiveData<Boolean> _runCalculationRequested = new MutableLiveData<>(false);
    public final LiveData<Boolean> runCalculationRequested = _runCalculationRequested;

    private final MutableLiveData<Boolean> _isCalculationRunning = new MutableLiveData<>(false);
    public final LiveData<Boolean> isCalculationRunning = _isCalculationRunning;

    @Inject
    public DisplayOptionsViewModel() {
    }

    public void setTrajectoryView(TrajectoryView view) {
        if (view != _trajectoryView.getValue()) {
            _trajectoryView.setValue(view);
        }
    }

    public TrajectoryView getTrajectoryView() {
        return _trajectoryView.getValue();
    }

    public void setShowDatasetLayer(boolean show) {
        if (!Boolean.valueOf(show).equals(_showDatasetLayer.getValue())) {
            _showDatasetLayer.setValue(show);
        }
    }

    public boolean isShowDatasetLayer() {
        return Boolean.TRUE.equals(_showDatasetLayer.getValue());
    }

    public void requestRunCalculation() {
        _runCalculationRequested.setValue(true);
    }

    public void clearRunCalculationRequest() {
        _runCalculationRequested.setValue(false);
    }

    public void setCalculationRunning(boolean running) {
        if (!Boolean.valueOf(running).equals(_isCalculationRunning.getValue())) {
            _isCalculationRunning.setValue(running);
        }
    }

    public boolean isCalculationRunning() {
        return Boolean.TRUE.equals(_isCalculationRunning.getValue());
    }
}