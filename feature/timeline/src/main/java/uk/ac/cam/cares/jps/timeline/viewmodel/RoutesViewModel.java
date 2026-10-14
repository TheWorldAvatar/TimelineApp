package uk.ac.cam.cares.jps.timeline.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import uk.ac.cam.cares.jps.data.RoutingRepository;
import uk.ac.cam.cares.jps.model.GeocodingResult;
import uk.ac.cam.cares.jps.model.RouteOption;
import uk.ac.cam.cares.jps.model.RouteProfile;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

// NEW FILE
/**
 * Holds all state of the route selection mode: which mode the map is in, the chosen start and end,
 * the travel profile, address suggestions, the suggested routes and which one is selected.
 * Same public-LiveData-field style as TripAgentViewModel / TrajectoryViewModel.
 */
@HiltViewModel
public class RoutesViewModel extends ViewModel {

    /** The two modes of the timeline screen. */
    public enum MapMode { RECORDING, ROUTE_SELECTION }

    /** Which point the next map tap or address selection fills. */
    public enum RouteTarget { START, END }

    private static final int MIN_SEARCH_LENGTH = 3;

    private final RoutingRepository routingRepository;

    private final MutableLiveData<MapMode> _mode = new MutableLiveData<>(MapMode.RECORDING);
    private final MutableLiveData<GeocodingResult> _start = new MutableLiveData<>();
    private final MutableLiveData<GeocodingResult> _end = new MutableLiveData<>();
    private final MutableLiveData<RouteTarget> _activeTarget = new MutableLiveData<>(RouteTarget.START);
    private final MutableLiveData<RouteProfile> _profile = new MutableLiveData<>(RouteProfile.DRIVING);
    private final MutableLiveData<List<RouteOption>> _routes = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Integer> _selectedRouteIndex = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> _isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> _errorMessage = new MutableLiveData<>();
    private final MutableLiveData<List<GeocodingResult>> _suggestions = new MutableLiveData<>(new ArrayList<>());

    public final LiveData<MapMode> mode = _mode;
    public final LiveData<GeocodingResult> start = _start;
    public final LiveData<GeocodingResult> end = _end;
    public final LiveData<RouteTarget> activeTarget = _activeTarget;
    public final LiveData<RouteProfile> profile = _profile;
    public final LiveData<List<RouteOption>> routes = _routes;
    public final LiveData<Integer> selectedRouteIndex = _selectedRouteIndex;
    public final LiveData<Boolean> isLoading = _isLoading;
    public final LiveData<String> errorMessage = _errorMessage;
    public final LiveData<List<GeocodingResult>> suggestions = _suggestions;

    // Every request gets a number; a response is ignored if a newer request was started meanwhile.
    // This stops slow, out-of-date answers overwriting newer state (typing quickly, changing the end point, ...).
    private int searchRequestId = 0;
    private int routeRequestId = 0;

    private static final boolean ROUTES_ENABLED = false; // flip to true when ready

    @Inject
    public RoutesViewModel(RoutingRepository routingRepository) {
        this.routingRepository = routingRepository;
    }

    // ---------------------------------------------------------------- mode

    public void setMode(MapMode newMode) {
        if (!ROUTES_ENABLED && newMode == MapMode.ROUTE_SELECTION) return;
        if (newMode != _mode.getValue()) {
            _mode.setValue(newMode);
        }
    }

    public boolean isRouteMode() {
        return _mode.getValue() == MapMode.ROUTE_SELECTION;
    }

    // ---------------------------------------------------------------- start / end

    public void setActiveTarget(RouteTarget target) {
        if (target != _activeTarget.getValue()) {
            _activeTarget.setValue(target);
        }
    }

    /** Sets (or with null, clears) the start or end point. Called for both address selection and map taps. */
    public void setPoint(RouteTarget target, GeocodingResult point) {
        if (target == RouteTarget.START) {
            _start.setValue(point);
            // Convenient flow: once the start is chosen, the next tap or address fills the end.
            if (point != null) {
                _activeTarget.setValue(RouteTarget.END);
            }
        } else {
            _end.setValue(point);
        }
        invalidateRoutes(); // old routes no longer match the chosen points
    }

    public void clearPoint(RouteTarget target) {
        setPoint(target, null);
    }

    /** A map tap fills whichever field is currently active. */
    public void onMapTapped(double longitude, double latitude) {
        RouteTarget target = _activeTarget.getValue() == null ? RouteTarget.START : _activeTarget.getValue();
        String label = String.format(Locale.US, "Dropped pin (%.5f, %.5f)", latitude, longitude);
        setPoint(target, new GeocodingResult(label, longitude, latitude));
    }

    public void setProfile(RouteProfile newProfile) {
        if (newProfile != _profile.getValue()) {
            _profile.setValue(newProfile);
            invalidateRoutes();
        }
    }

    // ---------------------------------------------------------------- address search

    /** Looks up address suggestions. The focus point (map centre) makes nearby results rank higher. */
    public void searchPlaces(String query, Double focusLongitude, Double focusLatitude) {
        String trimmed = query == null ? "" : query.trim();
        final int requestId = ++searchRequestId;

        if (trimmed.length() < MIN_SEARCH_LENGTH) {
            _suggestions.setValue(new ArrayList<>());
            return;
        }

        routingRepository.searchPlaces(trimmed, focusLongitude, focusLatitude, new RepositoryCallback<>() {
            @Override
            public void onSuccess(List<GeocodingResult> result) {
                if (requestId != searchRequestId) return; // a newer search has started
                _suggestions.postValue(result);
            }

            @Override
            public void onFailure(Throwable error) {
                if (requestId != searchRequestId) return;
                _suggestions.postValue(new ArrayList<>());
                _errorMessage.postValue("Address search failed. You can still tap the map to choose a point.");
            }
        });
    }

    // ---------------------------------------------------------------- routes

    public void findRoutes() {
        GeocodingResult startPoint = _start.getValue();
        GeocodingResult endPoint = _end.getValue();
        if (startPoint == null || endPoint == null) {
            _errorMessage.setValue("Choose a start and an end point first.");
            return;
        }

        final int requestId = ++routeRequestId;
        _isLoading.setValue(true);
        _errorMessage.setValue(null);

        routingRepository.getRoutes(_profile.getValue(),
                startPoint.getLongitude(), startPoint.getLatitude(),
                endPoint.getLongitude(), endPoint.getLatitude(),
                new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(List<RouteOption> result) {
                        if (requestId != routeRequestId) return;
                        _routes.postValue(result);
                        _selectedRouteIndex.postValue(0);
                        _isLoading.postValue(false);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        if (requestId != routeRequestId) return;
                        _isLoading.postValue(false);
                        _errorMessage.postValue("Could not find a route. Please try again.");
                    }
                });
    }

    public void selectRoute(int index) {
        List<RouteOption> current = _routes.getValue();
        if (current != null && index >= 0 && index < current.size()) {
            _selectedRouteIndex.setValue(index);
        }
    }

    /** Drops the current results and ignores any request still in flight. */
    private void invalidateRoutes() {
        routeRequestId++;
        _routes.setValue(new ArrayList<>());
        _selectedRouteIndex.setValue(0);
        _isLoading.setValue(false);
        _errorMessage.setValue(null);
    }
}