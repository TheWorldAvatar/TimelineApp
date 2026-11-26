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

import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.sensor.ui.RecordingViewModel;
import uk.ac.cam.cares.jps.timelinemap.databinding.FragmentUserDialogBinding;
import uk.ac.cam.cares.jps.ui.base.UiUtils;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.DisplayOptionsViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.UserAccountViewModel;

@AndroidEntryPoint
public class UserDialogFragment extends DialogFragment {
    private FragmentUserDialogBinding binding;
    private UserAccountViewModel userAccountViewModel;
    private RecordingViewModel recordingViewModel;
    private DisplayOptionsViewModel displayOptionsViewModel;

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
        displayOptionsViewModel = new ViewModelProvider(requireActivity()).get(DisplayOptionsViewModel.class);
        userAccountViewModel.registerForLogoutResult(this);
        binding.setLifecycleOwner(getViewLifecycleOwner());
        binding.setUserAccountViewModel(userAccountViewModel);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.closeDialogBt.setOnClickListener(v -> dismiss());

        userAccountViewModel.getName().observe(getViewLifecycleOwner(), name ->
                binding.userAvatarInitialsTv.setText(extractInitials(name)));

        recordingViewModel.getSelectedSensors().observe(getViewLifecycleOwner(), sensors -> {
            int active = sensors != null ? sensors.size() : 0;
            int total = SensorType.values().length;
            binding.sensorSettingSubtitle.setText(active + " of " + total + " sensors active");
        });

        displayOptionsViewModel.trajectoryView.observe(getViewLifecycleOwner(), mode -> {
            boolean isActivity = mode == DisplayOptionsViewModel.TrajectoryView.ACTIVITY;
            binding.displayOptionsSubtitle.setText(
                    isActivity ? "Colour by Activity mode" : "Colour by Trips & visits");
        });

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
        binding.displayOptionsSetting.setOnClickListener(v -> {
            dismiss();
            navigate(uk.ac.cam.cares.jps.utils.R.string.display_options_link);
        });
    }

    private String extractInitials(@Nullable String fullName) {
        if (fullName == null) return "TW";
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) return "TW";
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase(Locale.ROOT);
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
            float density = getResources().getDisplayMetrics().density;
            int maxWidthPx = Math.round(420 * density);
            params.width = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.90f), maxWidthPx);
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
    }
}