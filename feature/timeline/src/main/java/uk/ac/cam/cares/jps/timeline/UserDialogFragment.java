package uk.ac.cam.cares.jps.timeline;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavDeepLinkRequest;
import androidx.navigation.fragment.NavHostFragment;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.timeline.viewmodel.RoutesViewModel;   // CHANGE 1 of 4: ADD import
import uk.ac.cam.cares.jps.timelinemap.R;                          // CHANGE 1 of 4: ADD import (button ids for the toggle)
import uk.ac.cam.cares.jps.timelinemap.databinding.FragmentUserDialogBinding;
import uk.ac.cam.cares.jps.sensor.ui.RecordingViewModel;
import uk.ac.cam.cares.jps.ui.base.UiUtils;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.UserAccountViewModel;

@AndroidEntryPoint
public class UserDialogFragment extends DialogFragment {

    private FragmentUserDialogBinding binding;
    private UserAccountViewModel userAccountViewModel;
    private RecordingViewModel recordingViewModel;
    private RoutesViewModel routesViewModel;   // CHANGE 2 of 4: ADD field

    public static void show(@NonNull FragmentManager fragmentManager) {
        UserDialogFragment dialog = new UserDialogFragment();
        dialog.show(fragmentManager, "UserDialog");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentUserDialogBinding.inflate(inflater, container, false);
        userAccountViewModel = new ViewModelProvider(requireActivity()).get(UserAccountViewModel.class);
        recordingViewModel = new ViewModelProvider(this).get(RecordingViewModel.class);
        // CHANGE 3 of 4: ADD. The same activity-scoped instance TimelineFragment and the managers use,
        // fetched the same way this dialog already fetches UserAccountViewModel.
        routesViewModel = new ViewModelProvider(requireActivity()).get(RoutesViewModel.class);

        userAccountViewModel.registerForLogoutResult(this);

        binding.setLifecycleOwner(getViewLifecycleOwner());
        binding.setUserAccountViewModel(userAccountViewModel);
        return binding.getRoot();
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        userAccountViewModel.getLogoutStatus().observe(getViewLifecycleOwner(), status -> {
            if (status != null && Boolean.TRUE.equals(status.getFirst())) {
                recordingViewModel.clearManagers(status.getSecond());
                dismiss();
                userAccountViewModel.clearLogoutStatus();
                navigate(uk.ac.cam.cares.jps.utils.R.string.onboarding_fragment_link);
            } else {
                Toast.makeText(requireContext(), uk.ac.cam.cares.jps.loginmodule.R.string.cancel_logout, Toast.LENGTH_SHORT).show();
            }
        });

        binding.accountSetting.setOnClickListener(v -> {
            dismiss();
            navigate(uk.ac.cam.cares.jps.utils.R.string.account_setting_link);
        });

        binding.sensorSetting.setOnClickListener(v -> {
            dismiss();
            navigate(uk.ac.cam.cares.jps.utils.R.string.sensor_fragment_link);
        });

        binding.helpPage.setOnClickListener(v -> {
            dismiss();
            navigate(uk.ac.cam.cares.jps.utils.R.string.help_fragment_link);
        });

        binding.timelineSetting.setOnClickListener(v -> {
            dismiss();
            navigate(uk.ac.cam.cares.jps.utils.R.string.timeline_setting_link);
        });

        binding.privacySetting.setOnClickListener(v -> UiUtils.showNotImplementedDialog(requireContext()));
        binding.healthReport.setOnClickListener(v -> UiUtils.showNotImplementedDialog(requireContext()));
        binding.locationHistory.setOnClickListener(v -> UiUtils.showNotImplementedDialog(requireContext()));

        binding.logOut.setOnClickListener(v -> userAccountViewModel.logout());

        binding.exposureSetting.setOnClickListener(v -> {
            dismiss();
            navigate(uk.ac.cam.cares.jps.utils.R.string.exposure_setting_link);
        });

        // setupMapModeToggle();   // CHANGE 4a of 4: ADD call
    }

    // CHANGE 4b of 4: ADD method. The Recording | Routes selector.
    private void setupMapModeToggle() {
        // 1. Show the current mode. Done BEFORE adding the listener, so this does not count as a user click.
        binding.dialogModeToggle.check(routesViewModel.isRouteMode()
                ? R.id.dialog_mode_routes_button
                : R.id.dialog_mode_recording_button);

        // 2. React to the user's choice: switch the mode and close the dialog so the map screen is visible right away.
        //    TimelineFragment observes the mode and shows/hides the recording widgets and the route panel.
        binding.dialogModeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return; // the group also reports the button that was just unchecked

            RoutesViewModel.MapMode selected = checkedId == R.id.dialog_mode_routes_button
                    ? RoutesViewModel.MapMode.ROUTE_SELECTION
                    : RoutesViewModel.MapMode.RECORDING;

            routesViewModel.setMode(selected);
            dismiss();
        });
    }

    private void navigate(int uriResId) {
        Uri uri = Uri.parse(requireContext().getString(uriResId));
        NavDeepLinkRequest request = NavDeepLinkRequest.Builder
                .fromUri(uri)
                .build();
        NavHostFragment.findNavController(this).navigate(request);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
    }
}