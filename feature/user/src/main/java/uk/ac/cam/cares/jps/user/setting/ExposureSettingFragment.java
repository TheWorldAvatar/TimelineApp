package uk.ac.cam.cares.jps.user.setting;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.model.ExposureDataset;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.AppPreferenceViewModel;
import uk.ac.cam.cares.jps.user.databinding.FragmentExposureSettingBinding;

@AndroidEntryPoint
public class ExposureSettingFragment extends Fragment {

    public static final String ARG_PENDING_CALC_DATE = "pending_calc_date";
    public static final String RESULT_KEY_AUTO_CALCULATE = "exposure_settings_auto_calculate";
    public static final String RESULT_CALC_DATE = "calc_date";

    private FragmentExposureSettingBinding binding;
    private AppPreferenceViewModel appPreferenceViewModel;
    private ExposureDataset selectedDataset;
    private CalcType selectedCalcType;
    private String pendingCalcDate;

    private enum CalcType {
        TRAJECTORY_COUNT("Trajectory Count", "TrajectoryCount"),
        TRAJECTORY_AREA("Trajectory Area", "TrajectoryArea"),
        TRAJECTORY_AREA_WEIGHTED_SUM("Trajectory Area Weighted Sum", "TrajectoryAreaWeightedSum"),
        TRAJECTORY_RASTER_AVERAGE("Trajectory Raster Average", "TrajectoryRasterAverage"),
        TRAJECTORY_RASTER_AREA("Trajectory Raster Area", "TrajectoryRasterArea");

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
        public String toString() { return label; }
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

        pendingCalcDate = getArguments() != null ? getArguments().getString(ARG_PENDING_CALC_DATE) : null;
        if (pendingCalcDate != null && !pendingCalcDate.isEmpty()) {
            try {
                LocalDate date = LocalDate.parse(pendingCalcDate);
                String formatted = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
                binding.btnSave.setText("Save & Calculate (" + formatted + ")");
            } catch (Exception ignored) {
                binding.btnSave.setText("Save & Calculate");
            }
        }

        appPreferenceViewModel.getAvailableDatasets().observe(getViewLifecycleOwner(), datasets -> {
            ArrayAdapter<ExposureDataset> adapter = new ArrayAdapter<>(
                    requireContext(), android.R.layout.simple_list_item_1, datasets);
            binding.inputDataset.setAdapter(adapter);
            restoreDatasetSelection();
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

        appPreferenceViewModel.getExposureDataset().observe(getViewLifecycleOwner(),
                v -> restoreDatasetSelection());

        appPreferenceViewModel.getExposureCalcType().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputCalcType.getText().toString().isEmpty()) {
                CalcType c = CalcType.fromValue(v);
                if (c != null) {
                    selectedCalcType = c;
                    binding.inputCalcType.setText(c.toString(), false);
                } else {
                    binding.inputCalcType.setText(v, false);
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

            if (pendingCalcDate != null && !pendingCalcDate.isEmpty()) {
                Bundle result = new Bundle();
                result.putString(RESULT_CALC_DATE, pendingCalcDate);
                getParentFragmentManager().setFragmentResult(RESULT_KEY_AUTO_CALCULATE, result);
                Toast.makeText(requireContext(), "Saved — starting calculation…", Toast.LENGTH_SHORT).show();
                NavHostFragment.findNavController(this).navigateUp();
            } else {
                Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void restoreDatasetSelection() {
        if (binding == null || selectedDataset != null) return;
        List<ExposureDataset> datasets = appPreferenceViewModel.getAvailableDatasets().getValue();
        String savedIri = appPreferenceViewModel.getExposureDataset().getValue();
        if (datasets == null || datasets.isEmpty() || savedIri == null || savedIri.isEmpty()) return;
        for (ExposureDataset d : datasets) {
            if (d.getIri().equals(savedIri)) {
                selectedDataset = d;
                binding.inputDataset.setText(d.getLabel(), false);
                return;
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}