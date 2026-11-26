package uk.ac.cam.cares.jps.user.setting;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.AppPreferenceViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.DisplayOptionsViewModel;
import uk.ac.cam.cares.jps.user.databinding.FragmentDisplayOptionsBinding;

/**
 * Advanced Options screen, reached from the profile dialog.
 * Lets the user configure trajectory colouring, toggle the exposure dataset layer, and trigger calculation.
 */
@AndroidEntryPoint
public class DisplayOptionsFragment extends Fragment {

    private FragmentDisplayOptionsBinding binding;
    private DisplayOptionsViewModel displayOptionsViewModel;
    private AppPreferenceViewModel appPreferenceViewModel;
    private boolean suppressDatasetSwitchEvent = false;
    private String selectedDatasetTableName = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDisplayOptionsBinding.inflate(inflater, container, false);
        displayOptionsViewModel = new ViewModelProvider(requireActivity()).get(DisplayOptionsViewModel.class);
        appPreferenceViewModel = new ViewModelProvider(requireActivity()).get(AppPreferenceViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.displayTopAppbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        appPreferenceViewModel.loadExposureParams();

        // 1. Trajectory colour mode
        binding.displayOptionsToggle.check(
                displayOptionsViewModel.getTrajectoryView() == DisplayOptionsViewModel.TrajectoryView.ACTIVITY
                        ? binding.displayActivityButton.getId()
                        : binding.displayTripsVisitsButton.getId());

        binding.displayOptionsToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            displayOptionsViewModel.setTrajectoryView(checkedId == binding.displayActivityButton.getId()
                    ? DisplayOptionsViewModel.TrajectoryView.ACTIVITY
                    : DisplayOptionsViewModel.TrajectoryView.TRIPS_AND_VISITS);
        });

        // 2. Show Dataset switch
        appPreferenceViewModel.getExposureDatasetTableName().observe(getViewLifecycleOwner(), tableName -> {
            selectedDatasetTableName = tableName != null ? tableName : "";
            updateDatasetSubtitle();
        });

        appPreferenceViewModel.getExposureDataset().observe(getViewLifecycleOwner(), datasetName ->
                updateDatasetSubtitle());

        displayOptionsViewModel.showDatasetLayer.observe(getViewLifecycleOwner(), show -> {
            boolean checked = Boolean.TRUE.equals(show);
            if (binding.showDatasetSwitch.isChecked() != checked) {
                suppressDatasetSwitchEvent = true;
                binding.showDatasetSwitch.setChecked(checked);
                suppressDatasetSwitchEvent = false;
            }
        });

        binding.showDatasetSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressDatasetSwitchEvent) return;
            if (isChecked && selectedDatasetTableName.isEmpty()) {
                suppressDatasetSwitchEvent = true;
                binding.showDatasetSwitch.setChecked(false);
                suppressDatasetSwitchEvent = false;
                Toast.makeText(requireContext(),
                        "Pick a dataset in Exposure Calculation Settings first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            displayOptionsViewModel.setShowDatasetLayer(isChecked);
        });

        // 3. Run Calculation button
        displayOptionsViewModel.isCalculationRunning.observe(getViewLifecycleOwner(), isRunning -> {
            boolean running = Boolean.TRUE.equals(isRunning);
            binding.runCalculationButton.setEnabled(!running);
            binding.runCalculationButton.setText(running ? "Calculating…" : "Run Calculation");
        });

        binding.runCalculationButton.setOnClickListener(v -> {
            displayOptionsViewModel.requestRunCalculation();
            Toast.makeText(requireContext(), "Starting calculation…", Toast.LENGTH_SHORT).show();
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        });
    }

    private void updateDatasetSubtitle() {
        if (binding == null) return;
        String datasetName = appPreferenceViewModel.getExposureDataset().getValue();
        if (selectedDatasetTableName.isEmpty()) {
            binding.showDatasetSubtitle.setText("No dataset selected in Exposure Calculation Settings");
        } else if (datasetName != null && !datasetName.isEmpty()) {
            binding.showDatasetSubtitle.setText("Overlay: " + datasetName);
        } else {
            binding.showDatasetSubtitle.setText("Overlay the selected exposure dataset on the map");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}