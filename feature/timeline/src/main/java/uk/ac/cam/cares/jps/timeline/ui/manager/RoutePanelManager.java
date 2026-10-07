package uk.ac.cam.cares.jps.timeline.ui.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.mapbox.geojson.Point;
import com.mapbox.maps.MapView;

import java.util.List;

import uk.ac.cam.cares.jps.model.GeocodingResult;
import uk.ac.cam.cares.jps.model.RouteOption;
import uk.ac.cam.cares.jps.model.RouteProfile;
import uk.ac.cam.cares.jps.timeline.ui.adapter.PlaceSuggestionAdapter;
import uk.ac.cam.cares.jps.timeline.ui.adapter.RouteAdapter;
import uk.ac.cam.cares.jps.timeline.viewmodel.RoutesViewModel;
import uk.ac.cam.cares.jps.timeline.viewmodel.RoutesViewModel.RouteTarget;
import uk.ac.cam.cares.jps.timelinemap.R;

// NEW FILE
/**
 * Widget side of route selection mode (the map side is RouteManager). It wires up:
 *  - the start and end text fields with address suggestions (search-as-you-type, debounced)
 *  - the travel-mode buttons (driving / cycling / walking)
 *  - the "Find routes" button, progress bar and error text
 *  - the list of suggested routes
 * It only talks to RoutesViewModel; it never calls the network itself.
 * Showing/hiding the whole panel is done by TimelineFragment (it owns the mode switch).
 */
public class RoutePanelManager {
    private static final long SEARCH_DEBOUNCE_MS = 350;

    private final RoutesViewModel routesViewModel;
    private final MapView mapView;
    private final Context context;

    private final MaterialAutoCompleteTextView startInput;
    private final MaterialAutoCompleteTextView endInput;
    private final PlaceSuggestionAdapter startAdapter;
    private final PlaceSuggestionAdapter endAdapter;
    private final MaterialButtonToggleGroup profileToggle;
    private final MaterialButton findButton;
    private final LinearProgressIndicator progress;
    private final TextView errorText;
    private final MaterialCardView resultsCard;
    private final RouteAdapter routeAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private RouteTarget suggestionTarget = RouteTarget.START; // which field the latest suggestions belong to

    public RoutePanelManager(Fragment fragment, MapView mapView) {
        View root = fragment.requireView();
        this.context = fragment.requireContext();
        this.mapView = mapView;
        this.routesViewModel = new ViewModelProvider(fragment.requireActivity()).get(RoutesViewModel.class);

        startInput = root.findViewById(R.id.start_input);
        endInput = root.findViewById(R.id.end_input);
        profileToggle = root.findViewById(R.id.profile_toggle);
        findButton = root.findViewById(R.id.find_routes_button);
        progress = root.findViewById(R.id.route_progress);
        errorText = root.findViewById(R.id.route_error);
        resultsCard = root.findViewById(R.id.route_results_card);

        startAdapter = new PlaceSuggestionAdapter(context);
        endAdapter = new PlaceSuggestionAdapter(context);
        routeAdapter = new RouteAdapter(routesViewModel::selectRoute);

        RecyclerView routeList = root.findViewById(R.id.route_list);
        routeList.setLayoutManager(new LinearLayoutManager(context));
        routeList.setAdapter(routeAdapter);

        setupInput(startInput, startAdapter, RouteTarget.START);
        setupInput(endInput, endAdapter, RouteTarget.END);
        setupProfileToggle();
        findButton.setOnClickListener(v -> {
            hideKeyboard(findButton);
            routesViewModel.findRoutes();
        });

        LifecycleOwner owner = fragment.getViewLifecycleOwner();
        observeState(owner);

        // Don't let a pending delayed search fire after the screen is gone.
        owner.getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) {
                handler.removeCallbacksAndMessages(null);
            }
        });
    }

    // ------------------------------------------------------------------ start / end fields

    private void setupInput(MaterialAutoCompleteTextView input, PlaceSuggestionAdapter adapter, RouteTarget target) {
        input.setAdapter(adapter);

        // Search as the user types.
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { onTextEdited(target, s.toString()); }
        });

        // The user picked a suggestion.
        input.setOnItemClickListener((parent, view, position, id) -> {
            GeocodingResult picked = adapter.getItem(position);
            if (picked != null) {
                routesViewModel.setPoint(target, picked);
                hideKeyboard(input);
            }
        });

        // The field the user is editing is the field the next map tap will fill.
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                routesViewModel.setActiveTarget(target);
            }
        });
    }

    private void onTextEdited(RouteTarget target, String text) {
        GeocodingResult current = pointFor(target);

        // The text equals the chosen point's label: we set it ourselves (suggestion picked or map tapped). Not a search.
        if (current != null && current.getLabel().equals(text)) {
            return;
        }

        // The user is changing the text of an already chosen point, so that point is no longer valid.
        if (current != null) {
            routesViewModel.clearPoint(target);
        }

        // Debounce: wait until the user stops typing for a moment, so we don't send one request per keystroke.
        suggestionTarget = target;
        if (pendingSearch != null) {
            handler.removeCallbacks(pendingSearch);
        }
        pendingSearch = () -> {
            GeocodingResult now = pointFor(target);
            if (now != null && now.getLabel().equals(text)) {
                return; // text became a chosen point while we were waiting
            }
            // Bias results towards what the user is looking at on the map.
            Point center = mapView.getMapboxMap().getCameraState().getCenter();
            routesViewModel.searchPlaces(text, center.longitude(), center.latitude());
        };
        handler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private GeocodingResult pointFor(RouteTarget target) {
        return target == RouteTarget.START ? routesViewModel.start.getValue() : routesViewModel.end.getValue();
    }

    // ------------------------------------------------------------------ travel mode

    private void setupProfileToggle() {
        profileToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                routesViewModel.setProfile(profileForButton(checkedId));
            }
        });
    }

    // if/else instead of switch: R.id values are not compile-time constants in library modules.
    private RouteProfile profileForButton(int buttonId) {
        if (buttonId == R.id.profile_cycling_button) return RouteProfile.CYCLING;
        if (buttonId == R.id.profile_walking_button) return RouteProfile.WALKING;
        return RouteProfile.DRIVING;
    }

    private int buttonForProfile(RouteProfile profile) {
        if (profile == RouteProfile.CYCLING) return R.id.profile_cycling_button;
        if (profile == RouteProfile.WALKING) return R.id.profile_walking_button;
        return R.id.profile_driving_button;
    }

    // ------------------------------------------------------------------ observing the ViewModel

    private void observeState(LifecycleOwner owner) {
        // A point was set (by suggestion, map tap, or restored after rotation): show its label in the field.
        routesViewModel.start.observe(owner, point -> {
            showPoint(startInput, point);
            updateFindButton();
        });
        routesViewModel.end.observe(owner, point -> {
            showPoint(endInput, point);
            updateFindButton();
        });

        // After the start is chosen the active target moves to END: move the cursor there too.
        routesViewModel.activeTarget.observe(owner, target -> {
            MaterialAutoCompleteTextView input = target == RouteTarget.START ? startInput : endInput;
            if (input.isShown() && !input.hasFocus()) {
                input.requestFocus();
            }
        });

        // New address suggestions: put them in the dropdown of the field being typed in.
        routesViewModel.suggestions.observe(owner, suggestions -> {
            PlaceSuggestionAdapter adapter = suggestionTarget == RouteTarget.START ? startAdapter : endAdapter;
            MaterialAutoCompleteTextView input = suggestionTarget == RouteTarget.START ? startInput : endInput;
            adapter.setItems(suggestions);
            // The adapter's data arrives after the text changed, so the dropdown has to be opened explicitly.
            if (!suggestions.isEmpty() && input.hasFocus()) {
                input.showDropDown();
            }
        });

        routesViewModel.profile.observe(owner, profile -> {
            int buttonId = buttonForProfile(profile);
            if (profileToggle.getCheckedButtonId() != buttonId) {
                profileToggle.check(buttonId);
            }
        });

        routesViewModel.isLoading.observe(owner, loading -> {
            progress.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE);
            updateFindButton();
        });

        routesViewModel.errorMessage.observe(owner, message -> {
            errorText.setText(message);
            errorText.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
        });

        routesViewModel.routes.observe(owner, routes -> {
            Integer selected = routesViewModel.selectedRouteIndex.getValue();
            routeAdapter.submit(routes, selected == null ? 0 : selected);
            refreshResultsVisibility();
        });
        routesViewModel.selectedRouteIndex.observe(owner,
                index -> routeAdapter.setSelectedIndex(index == null ? 0 : index));
        routesViewModel.mode.observe(owner, mode -> refreshResultsVisibility());
    }

    private void showPoint(MaterialAutoCompleteTextView input, GeocodingResult point) {
        if (point == null) {
            return; // cleared because the user is typing: leave their text alone
        }
        if (!point.getLabel().contentEquals(input.getText())) {
            input.setText(point.getLabel(), false); // false = don't filter / pop up the suggestion list
        }
        input.dismissDropDown();
        hideKeyboard(input);
    }

    // "Find routes" needs both points and no request already running.
    private void updateFindButton() {
        boolean ready = routesViewModel.start.getValue() != null
                && routesViewModel.end.getValue() != null
                && !Boolean.TRUE.equals(routesViewModel.isLoading.getValue());
        findButton.setEnabled(ready);
    }

    // The result cards only show in route mode and when there is something to show.
    private void refreshResultsVisibility() {
        List<RouteOption> routes = routesViewModel.routes.getValue();
        boolean show = routesViewModel.isRouteMode() && routes != null && !routes.isEmpty();
        resultsCard.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}