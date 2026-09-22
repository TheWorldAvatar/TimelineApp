package uk.ac.cam.cares.jps.timeline.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import uk.ac.cam.cares.jps.data.ExposureFeatureInfoRepository;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltViewModel
public class ExposureFeatureInfoViewModel extends ViewModel {

    private final ExposureFeatureInfoRepository exposureFeatureInfoRepository;
    private final MutableLiveData<String> _timelineResults = new MutableLiveData<>();
    private final MutableLiveData<Throwable> _error = new MutableLiveData<>();

    public LiveData<String> timelineResults = _timelineResults;
    public LiveData<Throwable> error = _error;

    @Inject
    public ExposureFeatureInfoViewModel(ExposureFeatureInfoRepository exposureFeatureInfoRepository) {
        this.exposureFeatureInfoRepository = exposureFeatureInfoRepository;
    }

    public void getTimelineResults(long lowerbound, long upperbound) {
        exposureFeatureInfoRepository.getTimelineResults(lowerbound, upperbound, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                _error.postValue(null);
                _timelineResults.postValue(result);
            }

            @Override
            public void onFailure(Throwable throwable) {
                _error.postValue(throwable);
            }
        });
    }
}