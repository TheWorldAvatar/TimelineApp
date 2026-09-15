package uk.ac.cam.cares.jps.timeline.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import uk.ac.cam.cares.jps.data.HawkerCentreRepository;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltViewModel
public class HawkerCentreViewModel extends ViewModel {

    private final HawkerCentreRepository hawkerCentreRepository;
    private final MutableLiveData<String> _hawkerCentresGeoJson = new MutableLiveData<>();
    private final MutableLiveData<Throwable> _error = new MutableLiveData<>();

    public LiveData<String> hawkerCentresGeoJson = _hawkerCentresGeoJson;
    public LiveData<Throwable> error = _error;

    private boolean hasFetched = false;

    @Inject
    public HawkerCentreViewModel(HawkerCentreRepository hawkerCentreRepository) {
        this.hawkerCentreRepository = hawkerCentreRepository;
    }

    /** Fetch once and cache — this is static reference data, no need to re-fetch per navigation. */
    public void loadHawkerCentresIfNeeded() {
        if (hasFetched) return;
        hasFetched = true;

        hawkerCentreRepository.getHawkerCentres(new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                _hawkerCentresGeoJson.postValue(result);
            }

            @Override
            public void onFailure(Throwable error) {
                hasFetched = false; // allow retry
                _error.postValue(error);
            }
        });
    }
}