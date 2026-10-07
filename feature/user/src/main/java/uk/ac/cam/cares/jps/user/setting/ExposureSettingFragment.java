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

        // Datasets arrive asynchronously from the KG query.
        appPreferenceViewModel.getAvailableDatasets().observe(getViewLifecycleOwner(), datasets -> {
            // NOTE: the dropdown rows are rendered with ExposureDataset.toString(),
            // so make sure that method returns getLabel() and NOT the IRI.
            ArrayAdapter<ExposureDataset> adapter = new ArrayAdapter<>(
                    requireContext(), android.R.layout.simple_list_item_1, datasets);
            binding.inputDataset.setAdapter(adapter);

            // CHANGED: the old inline "saved IRI -> label" loop was moved into
            // restoreDatasetSelection() so both observers can share it.
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

        // CHANGED: this observer used to do binding.inputDataset.setText(v), which
        // wrote the raw IRI into the field whenever the saved/default IRI arrived
        // before (or without) a matching entry in the dataset list. It must never
        // touch the text field directly. It now just retries the label lookup,
        // because the saved IRI may arrive after the dataset list.
        appPreferenceViewModel.getExposureDataset().observe(getViewLifecycleOwner(),
                v -> restoreDatasetSelection());

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

            // The IRI is still what gets stored; it just never appears in the UI.
            appPreferenceViewModel.setExposureDataset(selectedDataset.getIri());
            appPreferenceViewModel.setExposureDatasetTableName(selectedDataset.getDerivedTableName());
            appPreferenceViewModel.setExposureCalcType(calcTypeValue);
            appPreferenceViewModel.setExposureDistance(distanceText);
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * CHANGED (new method): translate the saved dataset IRI into a dataset label.
     *
     * Two things load asynchronously: the list of datasets (KG query) and the saved
     * IRI (preferences). Either can arrive first, so both observers call this and it
     * only does something once BOTH are available.
     *
     * If the saved IRI is not in the list (e.g. an old default that no longer exists
     * in the KG), the field is left empty. The user then has to pick a dataset, and
     * the save button already enforces that, so the IRI is never displayed.
     */
    private void restoreDatasetSelection() {
        if (binding == null || selectedDataset != null) return; // view gone, or already resolved/picked

        List<ExposureDataset> datasets = appPreferenceViewModel.getAvailableDatasets().getValue();
        String savedIri = appPreferenceViewModel.getExposureDataset().getValue();
        if (datasets == null || datasets.isEmpty() || savedIri == null || savedIri.isEmpty()) return;

        for (ExposureDataset d : datasets) {
            if (d.getIri().equals(savedIri)) {
                selectedDataset = d;
                // 'false' = don't filter the dropdown list down to this one entry
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