package uk.ac.cam.cares.jps.timeline.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import uk.ac.cam.cares.jps.data.TripAgentRepository;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

import uk.ac.cam.cares.jps.timeline.viewmodel.TrajectoryViewModel;

@HiltViewModel
public class TripAgentViewModel extends ViewModel {

    private final TripAgentRepository tripAgentRepository;
    private final MutableLiveData<Boolean> _isRunning = new MutableLiveData<>(false);
    private final MutableLiveData<Throwable> _error = new MutableLiveData<>();

    public LiveData<Boolean> isRunning = _isRunning;
    public LiveData<Throwable> error = _error;

    @Inject
    public TripAgentViewModel(TripAgentRepository tripAgentRepository) {
        this.tripAgentRepository = tripAgentRepository;
    }

    public void runTripAgent(LocalDate date) {
        Long lowerbound = date != null ? TrajectoryViewModel.calculateLowerbound(date) : null; //in millis
        Long upperbound = date != null ? TrajectoryViewModel.calculateUpperbound(date) : null;
        _isRunning.setValue(true);
        tripAgentRepository.runTripAgent(lowerbound, upperbound, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                _isRunning.postValue(false);
                _error.postValue(null);
            }

            @Override
            public void onFailure(Throwable throwable) {
                _isRunning.postValue(false);
                _error.postValue(throwable);
            }
        });
    }
}