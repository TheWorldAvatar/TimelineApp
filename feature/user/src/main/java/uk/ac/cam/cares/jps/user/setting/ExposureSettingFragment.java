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

    private static final String[] CALC_TYPE_OPTIONS = {
            "TrajectoryCount",
            "TrajectoryArea",
            "TrajectoryAreaWeightedSum"
    };

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
            // if a dataset was already saved, resolve it against the loaded list so the
            // field shows the label rather than the raw table name before this fires
            String saved = appPreferenceViewModel.getExposureDataset().getValue();
            if (saved != null && !saved.isEmpty()) {
                for (ExposureDataset d : datasets) {
                    if (d.getId().equals(saved)) {
                        selectedDataset = d;
                        binding.inputDataset.setText(d.getLabel(), false);
                        break;
                    }
                }
            }
        });

        // ADD — track user's live selection from the dropdown
        binding.inputDataset.setOnItemClickListener((parent, v, position, id) ->
                selectedDataset = (ExposureDataset) parent.getItemAtPosition(position));


        ArrayAdapter<String> calcTypeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                CALC_TYPE_OPTIONS
        );
        binding.inputCalcType.setAdapter(calcTypeAdapter);

        // Pre-fill fields with whatever was saved before
        appPreferenceViewModel.getExposureDataset().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputDataset.getText().toString().isEmpty()) {
                binding.inputDataset.setText(v);
            }
        });
        appPreferenceViewModel.getExposureCalcType().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputCalcType.getText().toString().isEmpty()) {
                binding.inputCalcType.setText(v, false); // false = don't filter adapter
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
            String datasetValue = selectedDataset != null
                    ? selectedDataset.getId()
                    : binding.inputDataset.getText().toString();
            appPreferenceViewModel.setExposureDataset(datasetValue);
            appPreferenceViewModel.setExposureCalcType(binding.inputCalcType.getText().toString());
            appPreferenceViewModel.setExposureDistance(binding.inputDistance.getText().toString());
            Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
