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

@AndroidEntryPoint
public class ExposureSettingFragment extends Fragment {

    private FragmentExposureSettingBinding binding;
    private AppPreferenceViewModel appPreferenceViewModel;

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

        // Pre-fill fields with whatever was saved before
        appPreferenceViewModel.getExposureDataset().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputDataset.getText().toString().isEmpty()) {
                binding.inputDataset.setText(v);
            }
        });
        appPreferenceViewModel.getExposureCalcType().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputCalcType.getText().toString().isEmpty()) {
                binding.inputCalcType.setText(v);
            }
        });
        appPreferenceViewModel.getExposureDistance().observe(getViewLifecycleOwner(), v -> {
            if (v != null && !v.isEmpty() && binding.inputDistance.getText().toString().isEmpty()) {
                binding.inputDistance.setText(v);
            }
        });
        appPreferenceViewModel.loadExposureParams();

        binding.exposureTopAppbar.setNavigationOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        binding.btnSave.setOnClickListener(v -> {
            appPreferenceViewModel.setExposureDataset(binding.inputDataset.getText().toString());
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
