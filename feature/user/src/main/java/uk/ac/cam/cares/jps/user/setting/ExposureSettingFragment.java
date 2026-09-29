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
import uk.ac.cam.cares.jps.user.databinding.FragmentExposureSettingBinding;
import android.widget.ArrayAdapter;
import java.util.List;
import uk.ac.cam.cares.jps.model.ExposureDataset;

@AndroidEntryPoint
public class ExposureSettingFragment extends Fragment {

    private FragmentExposureSettingBinding binding;
    private AppPreferenceViewModel appPreferenceViewModel;
    private ExposureDataset selectedDataset;
    private CalcType selectedCalcType;

    private enum CalcType {
        TRAJECTORY_COUNT("Trajectory Count", "TrajectoryCount"),
        TRAJECTORY_AREA("Trajectory Area", "TrajectoryArea"),
        TRAJECTORY_AREA_WEIGHTED_SUM("Trajectory Area Weighted Sum", "TrajectoryAreaWeightedSum");

        private final String label;
        private final String value;

        CalcType(String label, String value) {
            this.label = label;
            this.value = value;
        }

        String getValue() { return value; }

        static CalcType fromValue(String value) {
            for (CalcType c : values()) {
                if (c.value.equals(value)) return c;
            }
            return null;
        }

        @Override
        public String toString() { return label; } // shown in the dropdown
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentExposureSettingBinding.inflate(inflater, container, false);
        appPreferenceViewModel = new ViewModelProvider(requireActivity()).get(AppPreferenceViewModel.class);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        appPreferenceViewModel.getAvailableDatasets().observe(getViewLifecycleOwner(), datasets -> {
            ArrayAdapter<ExposureDataset> adapter = new ArrayAdapter<>(
                    requireContext(), android.R.layout.simple_list_item_1, datasets);
            binding.inputDataset.setAdapter(adapter);
            String saved = appPreferenceViewModel.getExposureDataset().getValue();
            if (saved != null && !saved.isEmpty()) {
                for (ExposureDataset d : datasets) {
                    if (d.getIri().equals(saved)) {
                        selectedDataset = d;
                        binding.inputDataset.setText(d.getLabel(), false);
                        break;
                    }
                }
            }
        });

        binding.inputDataset.setOnItemClickListener((parent, v, position, id) ->
                selectedDataset = (ExposureDataset) parent.getItemAtPosition(position));

        ArrayAdapter<CalcType> calcTypeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                CalcType.values()
        );
        binding.inputCalcType.setAdapter(calcTypeAdapter);

        binding.inputCalcType.setOnItemClickListener((parent, v, position, id) ->
                selectedCalcType = (CalcType) parent.getItemAtPosition(position));

        appPreferenceViewModel.getExposureDataset().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputDataset.getText().toString().isEmpty()) {
                binding.inputDataset.setText(v);
            }
        });
        appPreferenceViewModel.getExposureCalcType().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputCalcType.getText().toString().isEmpty()) {
                CalcType c = CalcType.fromValue(v);
                if (c != null) {
                    selectedCalcType = c;
                    binding.inputCalcType.setText(c.toString(), false); // shows "Trajectory Count"
                } else {
                    binding.inputCalcType.setText(v, false); // fallback, shouldn't normally happen
                }
            }
        });
        appPreferenceViewModel.getExposureDistance().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputDistance.getText().toString().isEmpty()) {
                binding.inputDistance.setText(v);
            }
        });
        appPreferenceViewModel.loadExposureParams();
        appPreferenceViewModel.loadAvailableDatasets();

        binding.exposureTopAppbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        binding.btnSave.setOnClickListener(v -> {
            if (selectedDataset == null) {
                binding.inputDatasetLayout.setError("Please select a dataset from the list");
                Toast.makeText(requireContext(), "Please select a dataset from the list", Toast.LENGTH_SHORT).show();
                return;
            }
            binding.inputDatasetLayout.setError(null);

            String distanceText = binding.inputDistance.getText().toString().trim();
            double distanceValue;
            try {
                distanceValue = Double.parseDouble(distanceText);
            } catch (NumberFormatException e) {
                binding.inputDistanceLayout.setError("Enter a valid distance");
                Toast.makeText(requireContext(), "Enter a valid distance", Toast.LENGTH_SHORT).show();
                return;
            }
            if (distanceValue <= 0) {
                binding.inputDistanceLayout.setError("Distance must be greater than 0");
                Toast.makeText(requireContext(), "Distance must be greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }
            binding.inputDistanceLayout.setError(null);

            String calcTypeValue = selectedCalcType != null
                    ? selectedCalcType.getValue()
                    : binding.inputCalcType.getText().toString();

            appPreferenceViewModel.setExposureDataset(selectedDataset.getIri());
            appPreferenceViewModel.setExposureDatasetTableName(selectedDataset.getDerivedTableName());
            appPreferenceViewModel.setExposureCalcType(calcTypeValue);
            appPreferenceViewModel.setExposureDistance(distanceText);
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}