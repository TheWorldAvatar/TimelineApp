package uk.ac.cam.cares.jps.timeline.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.time.LocalDate;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import uk.ac.cam.cares.jps.data.TripAgentRepository;
import uk.ac.cam.cares.jps.data.ExposureCalculationAgentRepository;
import uk.ac.cam.cares.jps.sensor.data.SensorCollectionStateManagerRepository;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltViewModel
public class TripAgentViewModel extends ViewModel {

    private final TripAgentRepository tripAgentRepository;
    private final ExposureCalculationAgentRepository exposureCalculationAgentRepository;
    private final SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository;
    private final MutableLiveData<Boolean> _isRunning = new MutableLiveData<>(false);
    private final MutableLiveData<Throwable> _error = new MutableLiveData<>();

    public LiveData<Boolean> isRunning = _isRunning;
    public LiveData<Throwable> error = _error;

    @Inject
    public TripAgentViewModel(TripAgentRepository tripAgentRepository,
                               ExposureCalculationAgentRepository exposureCalculationAgentRepository,
                               SensorCollectionStateManagerRepository sensorCollectionStateManagerRepository) {
        this.tripAgentRepository = tripAgentRepository;
        this.exposureCalculationAgentRepository = exposureCalculationAgentRepository;
        this.sensorCollectionStateManagerRepository = sensorCollectionStateManagerRepository;
    }

    public void runTripAgent(LocalDate date) {
        Long lowerbound = date != null ? TrajectoryViewModel.calculateLowerbound(date) : null;
        Long upperbound = date != null ? TrajectoryViewModel.calculateUpperbound(date) : null;
        _isRunning.setValue(true);

        sensorCollectionStateManagerRepository.getDeviceId(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String deviceId) {
                runTripAgentForDevice(deviceId, lowerbound, upperbound);
            }

            @Override
            public void onFailure(Throwable error) {
                _isRunning.postValue(false);
                _error.postValue(new Throwable("Could not retrieve deviceId", error));
            }
        });
    }

    private void runTripAgentForDevice(String deviceId, Long lowerbound, Long upperbound) {
        tripAgentRepository.runTripAgent(lowerbound, upperbound, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                exposureCalculationAgentRepository.triggerCalculation(deviceId, lowerbound, upperbound, new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(String exposureResult) {
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

            @Override
            public void onFailure(Throwable throwable) {
                _isRunning.postValue(false);
                _error.postValue(throwable);
            }
        });
    }
}