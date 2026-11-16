package uk.ac.cam.cares.jps.user.setting;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.DisplayOptionsViewModel;
import uk.ac.cam.cares.jps.user.databinding.FragmentDisplayOptionsBinding;

// NEW FILE
/**
 * Display Options screen, reached from the profile dialog (same pattern as ExposureSettingFragment).
 * Lets the user choose how the recorded trajectory is coloured on the map: Trips & visits, or Activity.
 *
 * The choice is written to the activity-scoped DisplayOptionsViewModel; TrajectoryManager in feature:timeline
 * observes it and repaints, so the map is already updated when the user navigates back.
 */
@AndroidEntryPoint
public class DisplayOptionsFragment extends Fragment {

    private FragmentDisplayOptionsBinding binding;
    private DisplayOptionsViewModel displayOptionsViewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDisplayOptionsBinding.inflate(inflater, container, false);
        displayOptionsViewModel = new ViewModelProvider(requireActivity()).get(DisplayOptionsViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.displayTopAppbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        // 1. Show the current choice. Done BEFORE adding the listener, so this does not count as a user click.
        binding.displayOptionsToggle.check(
                displayOptionsViewModel.getTrajectoryView() == DisplayOptionsViewModel.TrajectoryView.ACTIVITY
                        ? binding.displayActivityButton.getId()
                        : binding.displayTripsVisitsButton.getId());

        // 2. Apply the choice as soon as it is toggled (no Save button needed).
        binding.displayOptionsToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return; // the group also reports the button that was just unchecked

            displayOptionsViewModel.setTrajectoryView(checkedId == binding.displayActivityButton.getId()
                    ? DisplayOptionsViewModel.TrajectoryView.ACTIVITY
                    : DisplayOptionsViewModel.TrajectoryView.TRIPS_AND_VISITS);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
