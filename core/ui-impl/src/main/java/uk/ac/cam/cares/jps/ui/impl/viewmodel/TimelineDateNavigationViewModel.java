package uk.ac.cam.cares.jps.ui.impl.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.time.LocalDate;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class TimelineDateNavigationViewModel extends ViewModel {
    private final MutableLiveData<LocalDate> _requestedDate = new MutableLiveData<>();
    public final LiveData<LocalDate> requestedDate = _requestedDate;

    @Inject
    public TimelineDateNavigationViewModel() {
    }

    public void requestDate(LocalDate date) {
        _requestedDate.setValue(date);
    }

    public void clearRequestedDate() {
        _requestedDate.setValue(null);
    }
}