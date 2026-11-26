package uk.ac.cam.cares.jps.timeline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.MapView;
import com.mapbox.maps.Style;
import com.mapbox.maps.plugin.Plugin;
import com.mapbox.maps.plugin.compass.CompassPlugin;
import com.mapbox.maps.plugin.scalebar.ScaleBarPlugin;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;
import uk.ac.cam.cares.jps.sensor.permission.PermissionHelper;
import uk.ac.cam.cares.jps.sensor.source.handler.SensorType;
import uk.ac.cam.cares.jps.sensor.ui.RecordingViewModel;
import uk.ac.cam.cares.jps.timeline.ui.manager.BottomSheetManager;
import uk.ac.cam.cares.jps.timeline.ui.manager.DatasetLayerManager;
import uk.ac.cam.cares.jps.timeline.ui.manager.TrajectoryManager;
import uk.ac.cam.cares.jps.timelinemap.R;
import uk.ac.cam.cares.jps.timelinemap.databinding.FragmentTimelineBinding;
import uk.ac.cam.cares.jps.ui.impl.tooltip.TooltipSequence;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.AppPreferenceViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.TooltipTriggerViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.UserAccountViewModel;

import android.net.Uri;
import androidx.core.view.GravityCompat;
import androidx.navigation.NavDeepLinkRequest;
import uk.ac.cam.cares.jps.timeline.viewmodel.NormalBottomSheetViewModel;
import uk.ac.cam.cares.jps.ui.impl.viewmodel.TimelineDateNavigationViewModel;

import android.util.TypedValue;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

@AndroidEntryPoint
public class TimelineFragment extends Fragment {
    private FragmentTimelineBinding binding;
    private final Logger LOGGER = Logger.getLogger(TimelineFragment.class);
    private MapView mapView;
    private BottomSheetBehavior<LinearLayoutCompat> bottomSheetBehavior;
    private ScaleBarPlugin scaleBarPlugin;
    private CompassPlugin compassPlugin;
    private CancellationSignal locationCancellation;
    private LocationListener singleUpdateListener;
    private RecordingViewModel recordingStateViewModel;
    private TooltipTriggerViewModel tooltipTriggerViewModel;
    private UserAccountViewModel accountViewModel;
    private PermissionHelper permissionHelper;
    private DatasetLayerManager datasetLayerManager;
    private TrajectoryManager trajectoryManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentTimelineBinding.inflate(inflater);
        setupMenu();
        setupBackPress();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mapView = binding.mapView;

        recordingStateViewModel = new ViewModelProvider(this).get(RecordingViewModel.class);
        tooltipTriggerViewModel = new ViewModelProvider(requireActivity()).get(TooltipTriggerViewModel.class);
        accountViewModel = new ViewModelProvider(this).get(UserAccountViewModel.class);
        permissionHelper = new PermissionHelper(this);

        setupAppPreference();
        setupTooltipsViewModel();
        setupRecordingButton();
        updateUIForThemeMode(isDarkModeEnabled());

        trajectoryManager = new TrajectoryManager(this, mapView);
        new BottomSheetManager(this, binding.bottomSheetContainer);
        NormalBottomSheetViewModel bottomSheetViewModel =
                new ViewModelProvider(this).get(NormalBottomSheetViewModel.class);
        TimelineDateNavigationViewModel dateNavViewModel =
                new ViewModelProvider(requireActivity()).get(TimelineDateNavigationViewModel.class);
        dateNavViewModel.requestedDate.observe(getViewLifecycleOwner(), date -> {
            if (date != null) {
                bottomSheetViewModel.setDate(date);
                dateNavViewModel.clearRequestedDate();
            }
        });
        datasetLayerManager = new DatasetLayerManager(this, mapView);

        setupTripDetailScroll();

        compassPlugin = mapView.getPlugin(Plugin.MAPBOX_COMPASS_PLUGIN_ID);
        if (compassPlugin != null) {
            compassPlugin.setEnabled(true);
            compassPlugin.updateSettings(settings -> {
                settings.setMarginTop(400);
                return null;
            });
        }

        scaleBarPlugin = mapView.getPlugin(Plugin.MAPBOX_SCALEBAR_PLUGIN_ID);
        if (scaleBarPlugin != null) {
            float marginLeftPx = 12f * getResources().getDisplayMetrics().density;
            float extraTopPx = 8f * getResources().getDisplayMetrics().density;
            float textSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 12f, getResources().getDisplayMetrics());

            scaleBarPlugin.updateSettings(settings -> {
                settings.setIsMetricUnits(true);
                settings.setTextSize(textSizePx);
                return null;
            });

            Runnable applyScaleBarInsets = () -> {
                int barBottom = binding.mapTopAppbar.getBottom();
                if (barBottom > 0) {
                    scaleBarPlugin.updateSettings(settings -> {
                        settings.setIsMetricUnits(true);
                        settings.setTextSize(textSizePx);
                        settings.setMarginTop(barBottom + extraTopPx);
                        settings.setMarginLeft(marginLeftPx);
                        return null;
                    });
                }
            };
            binding.mapTopAppbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (b > 0 && b != ob) applyScaleBarInsets.run();
            });
            binding.mapTopAppbar.post(applyScaleBarInsets);
        }

        recordingStateViewModel.getHasAccountError().observe(getViewLifecycleOwner(), hasError -> {
            if (Boolean.FALSE.equals(hasError)) {
                return;
            }
            if (isVisible()) {
                accountViewModel.getSessionExpiredDialog(this).show();
            }
        });
    }

    private void setupAppPreference() {
        AppPreferenceViewModel appPreferenceViewModel = new ViewModelProvider(requireActivity()).get(AppPreferenceViewModel.class);
        appPreferenceViewModel.getSkipTooltips().observe(getViewLifecycleOwner(), skipTooltips -> {
            if (skipTooltips != null && !skipTooltips) {
                tooltipTriggerViewModel.requestTooltipTrigger();
            }
        });

        appPreferenceViewModel.getAutoStart().observe(getViewLifecycleOwner(), shouldAutoStart -> {
            if (shouldAutoStart != null && shouldAutoStart) {
                autoStartRecordingIfPossible();
            }
        });

        appPreferenceViewModel.getLocationPermissionPrompted().observe(getViewLifecycleOwner(), isLocationPermissionPrompted -> {
            if (isLocationPermissionPrompted != null && isLocationPermissionPrompted) {
                return;
            }
            appPreferenceViewModel.setLocationPermissionPrompted();
            handleInitialLocationPrompt();
        });
    }

    private void setupTooltipsViewModel() {
        tooltipTriggerViewModel.getShouldTriggerTooltips().observe(getViewLifecycleOwner(), shouldTrigger -> {
            if (shouldTrigger) {
                tooltipTriggerViewModel.clearTrigger();
                showIntroTooltips();
            }
        });
    }

    private void showIntroTooltips() {
        View userIcon = binding.mapTopAppbar.findViewById(R.id.user_menu_item);
        View recordingFab = binding.recordingFab;
        View bottomSheet = binding.bottomSheetContainer;
        ViewTreeObserver observer = bottomSheet.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (userIcon.isShown() && recordingFab.isShown() && bottomSheet.isShown()) {
                    bottomSheet.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    TooltipSequence.launch(requireActivity(), userIcon, recordingFab, bottomSheet, userIcon);
                }
            }
        });
    }

    private void setupRecordingButton() {
        ExtendedFloatingActionButton fab = binding.recordingFab;
        positionRecordingFab(fab);
        recordingStateViewModel.getIsRecording().observe(getViewLifecycleOwner(), isRecording -> {
            if (isRecording != null) {
                fab.setIconResource(isRecording ? R.drawable.ic_stop : R.drawable.ic_start);
                fab.setText(isRecording ? R.string.stop : R.string.record);
                int bgColor = ContextCompat.getColor(requireContext(), isRecording ? R.color.fab_recording : R.color.fab_idle);
                fab.setBackgroundTintList(ColorStateList.valueOf(bgColor));
                positionRecordingFab(fab);
            }
        });

        fab.setOnClickListener(v -> {
            Boolean isRecording = recordingStateViewModel.getIsRecording().getValue();
            if (Boolean.TRUE.equals(isRecording)) {
                recordingStateViewModel.stopRecording();
            } else {
                List<SensorType> sensors = recordingStateViewModel.getSelectedSensors().getValue();
                if (sensors == null || sensors.isEmpty()) {
                    Toast.makeText(requireContext(), "No sensors enabled. Enable sensors in Sensor Settings first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> permissions = new ArrayList<>();
                if (sensors.contains(SensorType.LOCATION))
                    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                if (sensors.contains(SensorType.SOUND))
                    permissions.add(Manifest.permission.RECORD_AUDIO);
                if (sensors.contains(SensorType.ACTIVITY))
                    permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);

                permissionHelper.requestPermissionsInChain(permissions, recordingStateViewModel::startRecording);
            }
        });
    }

    private void positionRecordingFab(ExtendedFloatingActionButton fab) {
        fab.post(() -> {
            if (!isAdded()) return;
            float density = getResources().getDisplayMetrics().density;
            fab.setTranslationY(-(fab.getHeight() / 2f + 16f * density));
        });
    }

    private void handleInitialLocationPrompt() {
        permissionHelper.requestPermissionsInChain(
                List.of(Manifest.permission.ACCESS_FINE_LOCATION),
                () -> {
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                        getAndCenterUserLocation();
                    } else {
                        setCameraToDefaultLocation();
                        Toast.makeText(requireContext(), "Location permission denied. Showing default view.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @SuppressLint("MissingPermission")
    private void getAndCenterUserLocation() {
        LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        String provider = pickProvider(lm);
        if (lm == null || provider == null) {
            setCameraToDefaultLocation();
            return;
        }
        cancelLocationRequest();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationCancellation = new CancellationSignal();
            lm.getCurrentLocation(provider, locationCancellation,
                    ContextCompat.getMainExecutor(requireContext()), this::centerOnLocationOrDefault);
            return;
        }
        Location last = lm.getLastKnownLocation(provider);
        if (last != null) {
            centerOnLocationOrDefault(last);
            return;
        }
        singleUpdateListener = this::centerOnLocationOrDefault;
        lm.requestSingleUpdate(provider, singleUpdateListener, Looper.getMainLooper());
    }

    private String pickProvider(LocationManager lm) {
        if (lm == null) return null;
        List<String> candidates = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) candidates.add(LocationManager.FUSED_PROVIDER);
        candidates.add(LocationManager.GPS_PROVIDER);
        candidates.add(LocationManager.NETWORK_PROVIDER);
        for (String p : candidates) {
            try {
                if (lm.isProviderEnabled(p)) return p;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private void centerOnLocationOrDefault(@Nullable Location loc) {
        if (!isAdded() || mapView == null) return;
        if (loc != null) {
            mapView.getMapboxMap().setCamera(
                    new CameraOptions.Builder()
                            .center(Point.fromLngLat(loc.getLongitude(), loc.getLatitude()))
                            .zoom(15.0).build());
        } else {
            setCameraToDefaultLocation();
        }
    }

    private void cancelLocationRequest() {
        if (locationCancellation != null) {
            locationCancellation.cancel();
            locationCancellation = null;
        }
        if (singleUpdateListener != null && getContext() != null) {
            LocationManager lm = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(singleUpdateListener);
            singleUpdateListener = null;
        }
    }

    @Override
    public void onDestroyView() {
        cancelLocationRequest();
        super.onDestroyView();
    }

    private void setCameraToDefaultLocation() {
        CameraOptions defaultCam = new CameraOptions.Builder()
                .center(Point.fromLngLat(103.7764, 1.2966)).zoom(13.0).build();
        mapView.getMapboxMap().setCamera(defaultCam);
    }

    private void setupMenu() {
        binding.mapTopAppbar.setNavigationOnClickListener(view ->
                binding.timelineDrawerLayout.openDrawer(GravityCompat.START));

        binding.timelineNavView.setCheckedItem(R.id.nav_timeline_map);
        binding.timelineNavView.setNavigationItemSelectedListener(item -> {
            binding.timelineDrawerLayout.closeDrawer(GravityCompat.START);
            int id = item.getItemId();
            if (id == R.id.nav_timeline_map) {
                return true;
            }
            if (id == R.id.nav_calculated_exposures) {
                Uri uri = Uri.parse(getString(uk.ac.cam.cares.jps.utils.R.string.exposure_overview_link));
                NavDeepLinkRequest request = NavDeepLinkRequest.Builder.fromUri(uri).build();
                NavHostFragment.findNavController(this).navigate(request);
                return true;
            }
            if (id == R.id.nav_compare_trips) {
                Uri uri = Uri.parse(getString(uk.ac.cam.cares.jps.utils.R.string.compare_trips_link));
                NavDeepLinkRequest request = NavDeepLinkRequest.Builder.fromUri(uri).build();
                NavHostFragment.findNavController(this).navigate(request);
                return true;
            }
            return false;
        });

        final int navInitLeft = binding.timelineNavView.getPaddingLeft();
        final int navInitTop = binding.timelineNavView.getPaddingTop();
        final int navInitRight = binding.timelineNavView.getPaddingRight();
        final int navInitBottom = binding.timelineNavView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(binding.timelineNavView, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    navInitLeft + bars.left,
                    navInitTop + bars.top,
                    navInitRight + bars.right,
                    navInitBottom + bars.bottom);
            return insets; // keep insets flowing to map_top_appbar
        });
        ViewCompat.requestApplyInsets(binding.timelineNavView);

        binding.mapTopAppbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.user_menu_item) {
                UserDialogFragment.show(getParentFragmentManager());
                return true;
            }
            return false;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            binding.timelineNavView.setCheckedItem(R.id.nav_timeline_map);
        }
    }

    private void setupBackPress() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            private boolean doubleBackOnce;

            @Override
            public void handleOnBackPressed() {
                if (binding != null && binding.timelineDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.timelineDrawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                if (doubleBackOnce) {
                    requireActivity().finishAffinity();
                } else {
                    doubleBackOnce = true;
                    Toast.makeText(requireContext(), "Please click BACK again to exit", Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> doubleBackOnce = false, 2000);
                }
            }
        });
    }

    private void updateUIForThemeMode(boolean isDark) {
        mapView.getMapboxMap().loadStyleUri(isDark ? Style.DARK : Style.LIGHT);
    }

    private boolean isDarkModeEnabled() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void autoStartRecordingIfPossible() {
        List<SensorType> sensors = recordingStateViewModel.getSelectedSensors().getValue();
        if (sensors == null || sensors.isEmpty()) {
            Toast.makeText(requireContext(), "Auto-start skipped: no sensors enabled in Sensor Settings", Toast.LENGTH_SHORT).show();
            return;
        }
        recordingStateViewModel.startRecording();
        Toast.makeText(requireContext(), "Auto-start on", Toast.LENGTH_SHORT).show();
    }

    private void setupTripDetailScroll() {
        binding.tripDetailScroll.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
    }
}