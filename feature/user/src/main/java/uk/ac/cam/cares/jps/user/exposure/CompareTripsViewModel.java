package uk.ac.cam.cares.jps.user.exposure;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import uk.ac.cam.cares.jps.data.DatesWithTrajectoryRepository;
import uk.ac.cam.cares.jps.data.ExposureFeatureInfoRepository;
import uk.ac.cam.cares.jps.data.TrajectoryRepository;
import uk.ac.cam.cares.jps.model.YearMonthCompositeKey;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltViewModel
public class CompareTripsViewModel extends ViewModel {

    public static final int MAX_SELECTED_TRIPS = 4;
    private static final int MAX_CONCURRENT_DAY_REQUESTS = 3;
    private static final String PREFS_EXPOSURE_CACHE = "compare_trips_cache";
    private static final String PREFS_TRAJ_META_CACHE = "compare_trips_traj_cache";

    private static final Pattern STRICT_DISTANCE_KEY =
            Pattern.compile("^\\s*\\d+(?:\\.\\d+)?\\s*(?:km|m)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_VALUE =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(km|m)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_BRACKET =
            Pattern.compile("\\s*\\[(.*?)]\\s*$");
    private static final Pattern TRAILING_INT =
            Pattern.compile("(\\d+)$");

    public static final class MetricEntry {
        public final String dataset;
        public final String calcType;
        public final String buffer;
        @Nullable public final Double numericValue;
        public final String displayValue;
        public final String unit;
        public final boolean isAverage;

        MetricEntry(String dataset, String calcType, String buffer,
                    @Nullable Double numericValue, String displayValue, String unit,
                    boolean isAverage) {
            this.dataset = dataset;
            this.calcType = calcType;
            this.buffer = buffer;
            this.numericValue = numericValue;
            this.displayValue = displayValue;
            this.unit = unit;
            this.isAverage = isAverage;
        }
    }

    public static final class ComparableTrip {
        public final String id;
        public final LocalDate date;
        public final int tripCount;
        public final String title;
        public final String dateLabel;
        public final String durationLabel;
        public final String distanceLabel;
        public final String modeLabel;
        public final List<MetricEntry> metrics;

        ComparableTrip(String id, LocalDate date, int tripCount, String title, String dateLabel,
                       String durationLabel, String distanceLabel, String modeLabel,
                       List<MetricEntry> metrics) {
            this.id = id;
            this.date = date;
            this.tripCount = tripCount;
            this.title = title;
            this.dateLabel = dateLabel;
            this.durationLabel = durationLabel;
            this.distanceLabel = distanceLabel;
            this.modeLabel = modeLabel;
            this.metrics = metrics;
        }

        public ComparableTrip withDayMeta(String newDurationLabel, String newDistanceLabel, String newModeLabel) {
            return new ComparableTrip(
                    id, date, tripCount, title, dateLabel,
                    newDurationLabel, newDistanceLabel, newModeLabel, metrics);
        }

        public String formattedSummaryLine() {
            List<String> parts = new ArrayList<>();
            parts.add(tripCount == 1 ? "1 trip" : tripCount + " trips");
            if (!durationLabel.isEmpty()) parts.add(durationLabel);
            if (!distanceLabel.isEmpty()) parts.add(distanceLabel);
            if (!modeLabel.isEmpty()) parts.add(modeLabel);
            return String.join(" · ", parts);
        }

        @Nullable
        public MetricEntry findMetric(String dataset, String calcType, String buffer) {
            for (MetricEntry m : metrics) {
                if (m.dataset.equals(dataset) && m.calcType.equals(calcType) && m.buffer.equals(buffer)) {
                    return m;
                }
            }
            return null;
        }

        public List<String> datasetNames() {
            Set<String> names = new LinkedHashSet<>();
            for (MetricEntry m : metrics) {
                names.add(m.dataset);
            }
            return new ArrayList<>(names);
        }
    }

    private static final class DayMeta {
        final long durationMs;
        final int distanceMeters;
        final String modeLabel;

        DayMeta(long durationMs, int distanceMeters, String modeLabel) {
            this.durationMs = durationMs;
            this.distanceMeters = distanceMeters;
            this.modeLabel = modeLabel;
        }
    }

    private static final class MetricAccumulator {
        final String dataset;
        final String calcType;
        final String buffer;
        final boolean isAverage;
        String unit = "";
        String fallbackDisplay = "";
        final List<Double> values = new ArrayList<>();

        MetricAccumulator(String dataset, String calcType, String buffer, boolean isAverage) {
            this.dataset = dataset;
            this.calcType = calcType;
            this.buffer = buffer;
            this.isAverage = isAverage;
        }

        void add(MetricEntry entry) {
            if (unit.isEmpty() && !entry.unit.isEmpty()) unit = entry.unit;
            if (fallbackDisplay.isEmpty() && !entry.displayValue.isEmpty()) fallbackDisplay = entry.displayValue;
            if (entry.numericValue != null) {
                values.add(entry.numericValue);
            }
        }

        MetricEntry build() {
            if (values.isEmpty()) {
                return new MetricEntry(dataset, calcType, buffer, null,
                        fallbackDisplay.isEmpty() ? "–" : fallbackDisplay, unit, isAverage);
            }
            double total = 0.0;
            for (double v : values) total += v;
            double aggregated = isAverage ? (total / values.size()) : total;
            return new MetricEntry(dataset, calcType, buffer, aggregated,
                    formatAggregatedNumber(aggregated), unit, isAverage);
        }
    }

    private static final class Leaf {
        final List<String> path;
        final String value;

        Leaf(List<String> path, String value) {
            this.path = path;
            this.value = value;
        }
    }

    private final DatesWithTrajectoryRepository datesRepository;
    private final ExposureFeatureInfoRepository exposureInfoRepository;
    private final TrajectoryRepository trajectoryRepository;
    private final SharedPreferences exposureCachePrefs;
    private final SharedPreferences trajMetaCachePrefs;

    private final MutableLiveData<List<ComparableTrip>> _selectedTrips = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<YearMonth> _pickerMonth = new MutableLiveData<>(YearMonth.now());
    private final MutableLiveData<List<ComparableTrip>> _pickerTrips = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> _isPickerLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> _pickerError = new MutableLiveData<>();
    private final MutableLiveData<List<String>> _datasets = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> _selectedDataset = new MutableLiveData<>();
    private final MutableLiveData<List<String>> _buffers = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> _selectedBuffer = new MutableLiveData<>();

    public final LiveData<List<ComparableTrip>> selectedTrips = _selectedTrips;
    public final LiveData<YearMonth> pickerMonth = _pickerMonth;
    public final LiveData<List<ComparableTrip>> pickerTrips = _pickerTrips;
    public final LiveData<Boolean> isPickerLoading = _isPickerLoading;
    public final LiveData<String> pickerError = _pickerError;
    public final LiveData<List<String>> datasets = _datasets;
    public final LiveData<String> selectedDataset = _selectedDataset;
    public final LiveData<List<String>> buffers = _buffers;
    public final LiveData<String> selectedBuffer = _selectedBuffer;

    private Map<YearMonthCompositeKey, List<Integer>> cachedTrajectoryDates;
    private LocalDate latestRecordedDate;
    private int loadGeneration = 0;
    private boolean didAutoSelect = false;
    private long lastLoadedCalcMs = 0L;


    @Inject
    public CompareTripsViewModel(
            DatesWithTrajectoryRepository datesRepository,
            ExposureFeatureInfoRepository exposureInfoRepository,
            TrajectoryRepository trajectoryRepository,
            @ApplicationContext Context context) {
        this.datesRepository = datesRepository;
        this.exposureInfoRepository = exposureInfoRepository;
        this.trajectoryRepository = trajectoryRepository;
        this.exposureCachePrefs = context.getSharedPreferences(PREFS_EXPOSURE_CACHE, Context.MODE_PRIVATE);
        this.trajMetaCachePrefs = context.getSharedPreferences(PREFS_TRAJ_META_CACHE, Context.MODE_PRIVATE);
    }

    public void initIfNeeded() {
        if (cachedTrajectoryDates != null) return;
        loadDatesAndInitialMonth();
    }

    public void refreshIfInvalidated() {
        if (cachedTrajectoryDates == null) return;
        long latestCalcMs = exposureCachePrefs.getLong("__last_calc_ms", 0L);
        if (latestCalcMs > lastLoadedCalcMs) {
            YearMonth current = _pickerMonth.getValue();
            if (current != null) {
                loadPickerMonth(current, false); // only deleted dates get re-fetched
            }
        }
    }

    public void previousPickerMonth() {
        YearMonth current = _pickerMonth.getValue();
        if (current != null) {
            loadPickerMonth(current.minusMonths(1), false);
        }
    }

    public void nextPickerMonth() {
        YearMonth current = _pickerMonth.getValue();
        if (current != null && current.isBefore(YearMonth.now())) {
            loadPickerMonth(current.plusMonths(1), false);
        }
    }

    public void refreshPickerMonth() {
        YearMonth current = _pickerMonth.getValue();
        if (current != null) {
            loadPickerMonth(current, true);
        }
    }

    public void toggleTripSelection(ComparableTrip item) {
        List<ComparableTrip> current = new ArrayList<>(nonNull(_selectedTrips.getValue()));
        int existingIdx = indexOfTrip(current, item.id);
        if (existingIdx >= 0) {
            current.remove(existingIdx);
        } else {
            if (current.size() >= MAX_SELECTED_TRIPS) return;
            current.add(item);
        }
        _selectedTrips.setValue(current);
        recomputeAvailableFilters(current);
    }

    public void removeSelectedTrip(String id) {
        List<ComparableTrip> current = new ArrayList<>(nonNull(_selectedTrips.getValue()));
        int idx = indexOfTrip(current, id);
        if (idx >= 0) {
            current.remove(idx);
            _selectedTrips.setValue(current);
            recomputeAvailableFilters(current);
        }
    }

    public void makeBaseline(String id) {
        List<ComparableTrip> current = new ArrayList<>(nonNull(_selectedTrips.getValue()));
        int idx = indexOfTrip(current, id);
        if (idx > 0) {
            ComparableTrip chosen = current.remove(idx);
            current.add(0, chosen);
            _selectedTrips.setValue(current);
        }
    }

    public void setDataset(String dataset) {
        if (dataset == null || dataset.equals(_selectedDataset.getValue())) return;
        _selectedDataset.setValue(dataset);
        recomputeBuffersForActiveDataset(nonNull(_selectedTrips.getValue()), dataset);
    }

    public void setBuffer(String buffer) {
        if (buffer != null && !buffer.equals(_selectedBuffer.getValue())) {
            _selectedBuffer.setValue(buffer);
        }
    }

    private void loadDatesAndInitialMonth() {
        _isPickerLoading.setValue(true);
        _pickerError.setValue(null);
        String zoneId = ZoneId.systemDefault().getId();

        datesRepository.getDatesWithTrajectory(zoneId, new RepositoryCallback<>() {
            @Override
            public void onSuccess(Map<YearMonthCompositeKey, List<Integer>> result) {
                cachedTrajectoryDates = result;
                latestRecordedDate = findLatestRecordedDate(result);
                YearMonth initialMonth = latestRecordedDate != null
                        ? YearMonth.from(latestRecordedDate)
                        : YearMonth.now();
                loadPickerMonth(initialMonth, false);
            }

            @Override
            public void onFailure(Throwable throwable) {
                _isPickerLoading.postValue(false);
                _pickerError.postValue("Could not load trajectory dates.");
            }
        });
    }

    private void loadPickerMonth(YearMonth month, boolean forceRefresh) {
        int generation = ++loadGeneration;
        lastLoadedCalcMs = exposureCachePrefs.getLong("__last_calc_ms", 0L);
        _pickerMonth.setValue(month);
        _isPickerLoading.setValue(true);
        _pickerError.setValue(null);

        List<LocalDate> trajectoryDays = getSortedTrajectoryDatesInMonth(month);
        if (trajectoryDays.isEmpty()) {
            _pickerTrips.setValue(new ArrayList<>());
            _isPickerLoading.setValue(false);
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, String> dayJsonResults = new LinkedHashMap<>();
        Queue<LocalDate> toFetch = new ArrayDeque<>();

        for (LocalDate day : trajectoryDays) {
            boolean isLatestDay = day.equals(latestRecordedDate);
            String cachedJson = exposureCachePrefs.getString(day.toString(), null);
            if (!forceRefresh && !isLatestDay && cachedJson != null) {
                dayJsonResults.put(day, cachedJson);
            } else {
                toFetch.add(day);
            }
        }

        if (toFetch.isEmpty()) {
            finishPickerMonthLoad(dayJsonResults, generation, forceRefresh);
            return;
        }

        final int totalToFetch = toFetch.size();
        final int[] completed = {0};
        final int[] failed = {0};

        int initialWorkers = Math.min(MAX_CONCURRENT_DAY_REQUESTS, totalToFetch);
        for (int i = 0; i < initialWorkers; i++) {
            fetchNextExposureDay(toFetch, dayJsonResults, totalToFetch, completed, failed,
                    zone, generation, forceRefresh);
        }
    }

    private void fetchNextExposureDay(
            Queue<LocalDate> toFetch,
            Map<LocalDate, String> dayJsonResults,
            int totalToFetch,
            int[] completed,
            int[] failed,
            ZoneId zone,
            int generation,
            boolean forceRefresh) {
        if (generation != loadGeneration) return;
        LocalDate day;
        synchronized (toFetch) {
            day = toFetch.poll();
        }
        if (day == null) return;

        long lb = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long ub = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

        exposureInfoRepository.getTimelineResults(lb, ub, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                if (generation != loadGeneration) return;
                String safeJson = result != null ? result : "[]";
                exposureCachePrefs.edit().putString(day.toString(), safeJson).apply();
                synchronized (dayJsonResults) {
                    dayJsonResults.put(day, safeJson);
                }
                onDone(false);
            }

            @Override
            public void onFailure(Throwable throwable) {
                if (generation != loadGeneration) return;
                onDone(true);
            }

            private void onDone(boolean isFailure) {
                boolean allDone;
                synchronized (toFetch) {
                    if (isFailure) failed[0]++;
                    completed[0]++;
                    allDone = completed[0] >= totalToFetch;
                }
                if (allDone) {
                    if (failed[0] == totalToFetch && dayJsonResults.isEmpty()) {
                        _isPickerLoading.postValue(false);
                        _pickerError.postValue("Could not load calculated dates.");
                    } else {
                        finishPickerMonthLoad(dayJsonResults, generation, forceRefresh);
                    }
                } else {
                    fetchNextExposureDay(toFetch, dayJsonResults, totalToFetch, completed, failed,
                            zone, generation, forceRefresh);
                }
            }
        });
    }

    private void finishPickerMonthLoad(Map<LocalDate, String> dayJsonResults,
                                       int generation,
                                       boolean forceRefresh) {
        if (generation != loadGeneration) return;

        List<ComparableTrip> daysList = new ArrayList<>();
        Set<LocalDate> daysNeedingTrajectoryFetch = new LinkedHashSet<>();
        List<LocalDate> sortedDays = new ArrayList<>(dayJsonResults.keySet());
        sortedDays.sort(Comparator.reverseOrder());

        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter titleFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault());
        DateTimeFormatter shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());

        for (LocalDate day : sortedDays) {
            String json = dayJsonResults.get(day);
            if (json == null || json.isBlank()) continue;

            Map<String, MetricAccumulator> accumulators = new LinkedHashMap<>();
            int tripCount = 0;
            long fallbackDurationMs = 0L;

            try {
                JSONArray groups = new JSONArray(json);
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;

                    String key = group.optString("key", "");
                    int tripIndex = resolveTripIndex(group, key);
                    if (tripIndex <= 0 && !key.toLowerCase(Locale.ROOT).startsWith("trip")) {
                        continue; // skip stays
                    }

                    JSONObject results = group.optJSONObject("results");
                    if (results == null || results.length() == 0) continue;

                    List<MetricEntry> groupMetrics = parseGroupMetrics(results);
                    if (groupMetrics.isEmpty()) continue;

                    tripCount++;
                    long lbMs = parseTimestampMs(group.opt("lowerbound"));
                    long ubMs = parseTimestampMs(group.opt("upperbound"));
                    if (lbMs > 0 && ubMs > lbMs) {
                        fallbackDurationMs += (ubMs - lbMs);
                    }

                    for (MetricEntry entry : groupMetrics) {
                        String accKey = entry.dataset + "|" + entry.calcType + "|" + entry.buffer;
                        MetricAccumulator acc = accumulators.get(accKey);
                        if (acc == null) {
                            acc = new MetricAccumulator(
                                    entry.dataset, entry.calcType, entry.buffer, entry.isAverage);
                            accumulators.put(accKey, acc);
                        }
                        acc.add(entry);
                    }
                }
            } catch (JSONException ignored) {
            }

            if (tripCount == 0 || accumulators.isEmpty()) continue;

            List<MetricEntry> dayMetrics = new ArrayList<>(accumulators.size());
            for (MetricAccumulator acc : accumulators.values()) {
                dayMetrics.add(acc.build());
            }

            DayMeta cachedMeta = loadCachedDayMeta(day);
            boolean needTrajForDay = (cachedMeta == null)
                    || forceRefresh
                    || day.equals(latestRecordedDate);

            long durationMs = fallbackDurationMs;
            int distanceMeters = 0;
            String modeLabel = "";
            if (cachedMeta != null) {
                if (cachedMeta.durationMs > 0) durationMs = cachedMeta.durationMs;
                distanceMeters = cachedMeta.distanceMeters;
                modeLabel = cachedMeta.modeLabel;
            }

            daysList.add(new ComparableTrip(
                    day.toString(),
                    day,
                    tripCount,
                    day.format(titleFmt),
                    day.format(shortFmt),
                    formatDurationMs(durationMs),
                    formatDistance(distanceMeters),
                    modeLabel,
                    dayMetrics));

            if (needTrajForDay) {
                daysNeedingTrajectoryFetch.add(day);
            }
        }

        daysList.sort((a, b) -> b.date.compareTo(a.date));
        _pickerTrips.postValue(daysList);
        _isPickerLoading.postValue(false);

        if (!didAutoSelect && !daysList.isEmpty()) {
            didAutoSelect = true;
            List<ComparableTrip> initial = new ArrayList<>();
            int count = Math.min(3, daysList.size());
            for (int i = 0; i < count; i++) {
                initial.add(daysList.get(i));
            }
            _selectedTrips.postValue(initial);
            recomputeAvailableFilters(initial);
        } else {
            List<ComparableTrip> currentSelected = _selectedTrips.getValue();
            if (currentSelected != null && !currentSelected.isEmpty() && !daysList.isEmpty()) {
                Map<String, ComparableTrip> byId = new HashMap<>();
                for (ComparableTrip d : daysList) byId.put(d.id, d);

                boolean updatedAny = false;
                List<ComparableTrip> refreshed = new ArrayList<>(currentSelected.size());
                for (ComparableTrip sel : currentSelected) {
                    ComparableTrip newer = byId.get(sel.id);
                    if (newer != null) {
                        refreshed.add(newer);
                        updatedAny = true;
                    } else {
                        refreshed.add(sel);
                    }
                }
                if (updatedAny) {
                    _selectedTrips.postValue(refreshed);
                    recomputeAvailableFilters(refreshed);
                }
            }
        }

        if (!daysNeedingTrajectoryFetch.isEmpty()) {
            enrichDaysWithTrajectory(new ArrayDeque<>(daysNeedingTrajectoryFetch), zone, generation);
        }
    }

    private void enrichDaysWithTrajectory(Queue<LocalDate> queue, ZoneId zone, int generation) {
        if (generation != loadGeneration) return;
        LocalDate day;
        synchronized (queue) {
            day = queue.poll();
        }
        if (day == null) return;

        long lb = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long ub = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

        trajectoryRepository.getTrajectory(lb, ub, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                if (generation != loadGeneration) return;
                DayMeta parsedMeta = parseDayTrajectoryMeta(result);
                if (parsedMeta != null) {
                    saveCachedDayMeta(day, parsedMeta);
                    applyDayMetaToLiveLists(day, parsedMeta);
                }
                enrichDaysWithTrajectory(queue, zone, generation);
            }

            @Override
            public void onFailure(Throwable throwable) {
                if (generation != loadGeneration) return;
                enrichDaysWithTrajectory(queue, zone, generation);
            }
        });
    }

    private void applyDayMetaToLiveLists(LocalDate day, DayMeta meta) {
        List<ComparableTrip> picker = updateListWithMeta(_pickerTrips.getValue(), day, meta);
        if (picker != null) {
            _pickerTrips.postValue(picker);
        }

        List<ComparableTrip> selected = updateListWithMeta(_selectedTrips.getValue(), day, meta);
        if (selected != null) {
            _selectedTrips.postValue(selected);
        }
    }

    @Nullable
    private List<ComparableTrip> updateListWithMeta(
            @Nullable List<ComparableTrip> source, LocalDate day, DayMeta meta) {
        if (source == null || source.isEmpty()) return null;
        boolean changed = false;
        List<ComparableTrip> updated = new ArrayList<>(source.size());
        for (ComparableTrip item : source) {
            if (item.date.equals(day)) {
                updated.add(item.withDayMeta(
                        formatDurationMs(meta.durationMs),
                        formatDistance(meta.distanceMeters),
                        meta.modeLabel));
                changed = true;
            } else {
                updated.add(item);
            }
        }
        return changed ? updated : null;
    }

    @Nullable
    private DayMeta parseDayTrajectoryMeta(@Nullable String trajectoryJson) {
        if (trajectoryJson == null || trajectoryJson.isBlank()) return null;
        try {
            JSONObject root = new JSONObject(trajectoryJson);
            JSONArray features = root.optJSONArray("features");
            if (features == null) return null;

            Map<Integer, long[]> tripBounds = new HashMap<>();
            int totalDistanceMeters = 0;
            Map<String, Long> modeDurations = new HashMap<>();

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) continue;
                JSONObject props = feature.optJSONObject("properties");
                if (props == null) continue;

                int tripIdx = props.optInt("trip", -1);
                if (tripIdx <= 0) continue;

                long start = props.optLong("start_time", 0L);
                long end = props.optLong("end_time", 0L);
                int dist = Math.max(0, props.optInt("distance_traveled", 0));
                String activity = props.optString("activity_type", "");

                if (start > 0 && end > start) {
                    long[] bounds = tripBounds.get(tripIdx);
                    if (bounds == null) {
                        tripBounds.put(tripIdx, new long[]{start, end});
                    } else {
                        bounds[0] = Math.min(bounds[0], start);
                        bounds[1] = Math.max(bounds[1], end);
                    }
                }

                if (!"still".equalsIgnoreCase(activity)) {
                    totalDistanceMeters += dist;
                }

                if (!activity.isEmpty() && !"still".equalsIgnoreCase(activity) && !"unknown".equalsIgnoreCase(activity)) {
                    long segMs = Math.max(1L, end - start);
                    modeDurations.put(activity, modeDurations.getOrDefault(activity, 0L) + segMs);
                }
            }

            long totalDurationMs = 0L;
            for (long[] bounds : tripBounds.values()) {
                totalDurationMs += Math.max(0L, bounds[1] - bounds[0]);
            }

            return new DayMeta(totalDurationMs, totalDistanceMeters, pickDominantMode(modeDurations));
        } catch (JSONException ignored) {
            return null;
        }
    }

    private void saveCachedDayMeta(LocalDate day, DayMeta meta) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("dur", meta.durationMs);
            obj.put("dist", meta.distanceMeters);
            obj.put("mode", meta.modeLabel);
            trajMetaCachePrefs.edit().putString("day|" + day, obj.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    @Nullable
    private DayMeta loadCachedDayMeta(LocalDate day) {
        String raw = trajMetaCachePrefs.getString("day|" + day, null);
        if (raw == null) return null;
        try {
            JSONObject obj = new JSONObject(raw);
            return new DayMeta(
                    obj.optLong("dur", 0L),
                    obj.optInt("dist", 0),
                    obj.optString("mode", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickDominantMode(Map<String, Long> durations) {
        if (durations.isEmpty()) return "";
        String bestKey = null;
        long bestVal = -1L;
        for (Map.Entry<String, Long> e : durations.entrySet()) {
            if (e.getValue() > bestVal) {
                bestVal = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (bestKey == null) return "";
        switch (bestKey.toLowerCase(Locale.ROOT)) {
            case "walking":
            case "walk":
                return "Walk";
            case "bike":
            case "bicycle":
            case "on_bicycle":
                return "Bike";
            case "vehicle":
            case "in_vehicle":
                return "Vehicle";
            default:
                return Character.toUpperCase(bestKey.charAt(0)) + bestKey.substring(1);
        }
    }

    private void recomputeAvailableFilters(List<ComparableTrip> days) {
        Set<String> datasetSet = new LinkedHashSet<>();
        for (ComparableTrip day : days) {
            for (MetricEntry m : day.metrics) {
                datasetSet.add(m.dataset);
            }
        }
        List<String> dsList = new ArrayList<>(datasetSet);
        _datasets.postValue(dsList);

        String activeDataset = _selectedDataset.getValue();
        if (activeDataset == null || !dsList.contains(activeDataset)) {
            activeDataset = dsList.isEmpty() ? null : dsList.get(0);
            _selectedDataset.postValue(activeDataset);
        }
        recomputeBuffersForActiveDataset(days, activeDataset);
    }

    private void recomputeBuffersForActiveDataset(List<ComparableTrip> days, @Nullable String activeDataset) {
        if (activeDataset == null) {
            _buffers.postValue(Collections.emptyList());
            _selectedBuffer.postValue(null);
            return;
        }
        Set<String> bufferSet = new LinkedHashSet<>();
        for (ComparableTrip day : days) {
            for (MetricEntry m : day.metrics) {
                if (m.dataset.equals(activeDataset)) {
                    bufferSet.add(m.buffer);
                }
            }
        }
        List<String> bufferList = new ArrayList<>(bufferSet);
        bufferList.sort(CompareTripsViewModel::compareDistanceKeys);
        _buffers.postValue(bufferList);

        String activeBuffer = _selectedBuffer.getValue();
        if (activeBuffer == null || !bufferList.contains(activeBuffer)) {
            _selectedBuffer.postValue(bufferList.isEmpty() ? null : bufferList.get(0));
        }
    }

    private List<MetricEntry> parseGroupMetrics(JSONObject results) {
        List<MetricEntry> out = new ArrayList<>();
        Iterator<String> datasetKeys = results.keys();
        while (datasetKeys.hasNext()) {
            String dataset = datasetKeys.next();
            JSONObject root = results.optJSONObject(dataset);
            if (root == null) continue;

            List<Leaf> leaves = new ArrayList<>();
            collectLeaves(root, new ArrayList<>(), leaves);

            for (Leaf leaf : leaves) {
                int distanceIdx = -1;
                for (int i = 0; i < leaf.path.size(); i++) {
                    String k = leaf.path.get(i);
                    if (k.equals("-") || STRICT_DISTANCE_KEY.matcher(k).matches()) {
                        distanceIdx = i;
                        break;
                    }
                }
                String calcType;
                String buffer;
                if (distanceIdx < 0) {
                    calcType = String.join(" · ", leaf.path);
                    buffer = "-";
                } else {
                    calcType = String.join(" · ", leaf.path.subList(0, distanceIdx));
                    buffer = leaf.path.get(distanceIdx).trim();
                }
                if (calcType.isEmpty()) calcType = dataset;

                String unit = "";
                Matcher unitMatcher = UNIT_BRACKET.matcher(leaf.value);
                if (unitMatcher.find()) {
                    String rawUnit = unitMatcher.group(1);
                    if (rawUnit != null && !rawUnit.equals("-")) {
                        unit = rawUnit.trim();
                    }
                }
                String cleaned = UNIT_BRACKET.matcher(leaf.value).replaceAll("").trim();
                Double numeric = null;
                try {
                    numeric = Double.parseDouble(cleaned);
                } catch (NumberFormatException ignored) {
                }

                out.add(new MetricEntry(
                        dataset, calcType, buffer, numeric, cleaned, unit, isAverageCalcType(calcType)));
            }
        }
        return out;
    }

    private void collectLeaves(JSONObject node, List<String> path, List<Leaf> out) {
        Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("collapse".equals(key) || "display_order".equals(key)) continue;
            Object child = node.opt(key);
            if (child == null || child == JSONObject.NULL || child instanceof JSONArray) continue;

            List<String> next = new ArrayList<>(path);
            next.add(key);
            if (child instanceof JSONObject) {
                collectLeaves((JSONObject) child, next, out);
            } else {
                out.add(new Leaf(next, String.valueOf(child)));
            }
        }
    }

    private List<LocalDate> getSortedTrajectoryDatesInMonth(YearMonth month) {
        List<LocalDate> days = new ArrayList<>();
        if (cachedTrajectoryDates == null) return days;
        for (Map.Entry<YearMonthCompositeKey, List<Integer>> entry : cachedTrajectoryDates.entrySet()) {
            YearMonthCompositeKey key = entry.getKey();
            if (key.year() == month.getYear() && key.month() == month.getMonthValue()) {
                for (Integer dom : entry.getValue()) {
                    if (dom != null && dom >= 1 && dom <= month.lengthOfMonth()) {
                        days.add(month.atDay(dom));
                    }
                }
            }
        }
        days.sort(Comparator.reverseOrder());
        return days;
    }

    @Nullable
    private LocalDate findLatestRecordedDate(Map<YearMonthCompositeKey, List<Integer>> map) {
        LocalDate latest = null;
        for (Map.Entry<YearMonthCompositeKey, List<Integer>> entry : map.entrySet()) {
            YearMonth ym = YearMonth.of(entry.getKey().year(), entry.getKey().month());
            for (Integer dom : entry.getValue()) {
                if (dom == null || dom < 1 || dom > ym.lengthOfMonth()) continue;
                LocalDate d = ym.atDay(dom);
                if (latest == null || d.isAfter(latest)) {
                    latest = d;
                }
            }
        }
        return latest;
    }

    private static boolean isAverageCalcType(String calcType) {
        String lower = calcType.toLowerCase(Locale.ROOT);
        return lower.contains("average")
                || lower.contains("avg")
                || lower.contains("mean")
                || lower.contains("ndvi")
                || lower.contains("gvi")
                || lower.contains("utci")
                || lower.contains("index")
                || lower.contains("rate");
    }

    private static String formatAggregatedNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-6) {
            return String.format(Locale.getDefault(), "%d", Math.round(value));
        }
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.getDefault());
        String pattern = Math.abs(value) < 10.0 ? "0.###" : "#,##0.##";
        return new DecimalFormat(pattern, symbols).format(value);
    }

    private static int resolveTripIndex(JSONObject group, String key) {
        int tripIndex = group.optInt("trip", 0);
        if (tripIndex > 0) return tripIndex;
        if (key != null) {
            Matcher m = TRAILING_INT.matcher(key);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return tripIndex;
    }

    private static long parseTimestampMs(@Nullable Object raw) {
        if (raw == null || raw == JSONObject.NULL) return 0L;
        if (raw instanceof Number) {
            long val = ((Number) raw).longValue();
            return val < 1_000_000_000_000L ? val * 1000L : val;
        }
        String str = String.valueOf(raw).trim();
        if (str.isEmpty() || "null".equalsIgnoreCase(str)) return 0L;
        try {
            long val = Long.parseLong(str);
            return val < 1_000_000_000_000L ? val * 1000L : val;
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(str).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(str).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String formatDurationMs(long durationMs) {
        if (durationMs <= 0) return "";
        long mins = Math.max(1L, Math.round(durationMs / 60000.0));
        if (mins < 60) return mins + " min";
        long hrs = mins / 60;
        long rem = mins % 60;
        return rem == 0 ? hrs + "h" : hrs + "h " + rem + "m";
    }

    private static String formatDistance(int meters) {
        if (meters <= 0) return "";
        if (meters >= 1000) {
            return String.format(Locale.getDefault(), "%.1f km", meters / 1000.0);
        }
        return meters + " m";
    }

    private static int indexOfTrip(List<ComparableTrip> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private static <T> List<T> nonNull(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static double distanceInMetres(String key) {
        Matcher m = DISTANCE_VALUE.matcher(key);
        if (!m.find()) return Double.MAX_VALUE;
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        return (unit != null && unit.equalsIgnoreCase("km")) ? value * 1000.0 : value;
    }

    public static int compareDistanceKeys(String a, String b) {
        int cmp = Double.compare(distanceInMetres(a), distanceInMetres(b));
        return cmp != 0 ? cmp : a.compareToIgnoreCase(b);
    }
}