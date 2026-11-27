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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import uk.ac.cam.cares.jps.model.YearMonthCompositeKey;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltViewModel
public class WeeklyTrendsViewModel extends ViewModel {

    private static final int MAX_CONCURRENT_REQUESTS = 3;
    private static final String PREFS_EXPOSURE_CACHE = "compare_trips_cache";

    private static final Pattern STRICT_DISTANCE_KEY =
            Pattern.compile("^\\s*\\d+(?:\\.\\d+)?\\s*(?:km|m)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_BRACKET = Pattern.compile("\\s*\\[(.*?)]\\s*$");
    private static final Pattern TRAILING_INT = Pattern.compile("(\\d+)$");

    public enum AggregationMode {
        MEAN, TOTAL, PEAK
    }

    public static final class DayMetricValue {
        public final String dataset;
        public final String calcType;
        public final String buffer;
        public final double value;
        public final String unit;
        public final boolean isAverage;

        DayMetricValue(String dataset, String calcType, String buffer,
                       double value, String unit, boolean isAverage) {
            this.dataset = dataset;
            this.calcType = calcType;
            this.buffer = buffer;
            this.value = value;
            this.unit = unit;
            this.isAverage = isAverage;
        }
    }

    public static final class DayExposure {
        public final LocalDate date;
        public final int tripCount;
        public final List<DayMetricValue> metrics;

        DayExposure(LocalDate date, int tripCount, List<DayMetricValue> metrics) {
            this.date = date;
            this.tripCount = tripCount;
            this.metrics = metrics;
        }

        @Nullable
        public DayMetricValue find(String dataset, String calcType, String buffer) {
            for (DayMetricValue m : metrics) {
                if (m.dataset.equals(dataset)
                        && m.calcType.equals(calcType)
                        && m.buffer.equals(buffer)) {
                    return m;
                }
            }
            return null;
        }
    }

    public static final class WeekPoint {
        public final String weekKey;       // e.g. "2026-W47"
        public final String weekLabel;     // e.g. "W47"
        public final String dateRange;     // e.g. "16 Nov – 22 Nov"
        public final LocalDate weekStart;
        public final double displayValue;
        public final double minValue;
        public final double maxValue;
        public final String unit;
        public final int calculatedDays;
        public final int totalTrips;
        public final List<DayExposure> days;

        WeekPoint(String weekKey, String weekLabel, String dateRange, LocalDate weekStart,
                  double displayValue, double minValue, double maxValue, String unit,
                  int calculatedDays, int totalTrips, List<DayExposure> days) {
            this.weekKey = weekKey;
            this.weekLabel = weekLabel;
            this.dateRange = dateRange;
            this.weekStart = weekStart;
            this.displayValue = displayValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.unit = unit;
            this.calculatedDays = calculatedDays;
            this.totalTrips = totalTrips;
            this.days = days;
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

    private static final class DayAccumulator {
        final String dataset;
        final String calcType;
        final String buffer;
        final boolean isAverage;
        String unit = "";
        double sum = 0.0;
        int count = 0;

        DayAccumulator(String dataset, String calcType, String buffer, boolean isAverage) {
            this.dataset = dataset;
            this.calcType = calcType;
            this.buffer = buffer;
            this.isAverage = isAverage;
        }

        void add(double v, String u) {
            if (unit.isEmpty() && u != null && !u.isEmpty()) unit = u;
            sum += v;
            count++;
        }

        DayMetricValue build() {
            double val = isAverage ? (sum / Math.max(1, count)) : sum;
            return new DayMetricValue(dataset, calcType, buffer, val, unit, isAverage);
        }
    }

    private final DatesWithTrajectoryRepository datesRepository;
    private final ExposureFeatureInfoRepository exposureInfoRepository;
    private final SharedPreferences exposureCachePrefs;

    private final MutableLiveData<Boolean> _isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> _error = new MutableLiveData<>();
    private final MutableLiveData<Integer> _weekWindow = new MutableLiveData<>(12);
    private final MutableLiveData<AggregationMode> _aggregation = new MutableLiveData<>(AggregationMode.MEAN);
    private final MutableLiveData<List<String>> _datasets = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> _selectedDataset = new MutableLiveData<>();
    private final MutableLiveData<List<String>> _calcTypes = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> _selectedCalcType = new MutableLiveData<>();
    private final MutableLiveData<List<String>> _buffers = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> _selectedBuffer = new MutableLiveData<>();
    private final MutableLiveData<List<WeekPoint>> _weekPoints = new MutableLiveData<>(new ArrayList<>());

    public final LiveData<Boolean> isLoading = _isLoading;
    public final LiveData<String> error = _error;
    public final LiveData<Integer> weekWindow = _weekWindow;
    public final LiveData<AggregationMode> aggregation = _aggregation;
    public final LiveData<List<String>> datasets = _datasets;
    public final LiveData<String> selectedDataset = _selectedDataset;
    public final LiveData<List<String>> calcTypes = _calcTypes;
    public final LiveData<String> selectedCalcType = _selectedCalcType;
    public final LiveData<List<String>> buffers = _buffers;
    public final LiveData<String> selectedBuffer = _selectedBuffer;
    public final LiveData<List<WeekPoint>> weekPoints = _weekPoints;

    private Map<YearMonthCompositeKey, List<Integer>> cachedTrajectoryDates;
    private LocalDate latestRecordedDate;
    private final List<DayExposure> loadedDays = new ArrayList<>();
    private int loadGeneration = 0;
    private long lastLoadedCalcMs = 0L;

    @Inject
    public WeeklyTrendsViewModel(
            DatesWithTrajectoryRepository datesRepository,
            ExposureFeatureInfoRepository exposureInfoRepository,
            @ApplicationContext Context context) {
        this.datesRepository = datesRepository;
        this.exposureInfoRepository = exposureInfoRepository;
        this.exposureCachePrefs = context.getSharedPreferences(PREFS_EXPOSURE_CACHE, Context.MODE_PRIVATE);
    }

    public void initIfNeeded() {
        if (cachedTrajectoryDates != null) return;
        loadDatesAndFetchWindow(false);
    }

    public void refresh() {
        loadDatesAndFetchWindow(true);
    }

    public void refreshIfInvalidated() {
        if (cachedTrajectoryDates == null) return;
        long latestCalcMs = exposureCachePrefs.getLong("__last_calc_ms", 0L);
        if (latestCalcMs > lastLoadedCalcMs) {
            loadDatesAndFetchWindow(false);
        }
    }

    public void setWeekWindow(int weeks) {
        if (weeks == nonNull(_weekWindow.getValue(), 12)) return;
        _weekWindow.setValue(weeks);
        if (cachedTrajectoryDates != null) {
            fetchDaysInWindow(weeks, false, ++loadGeneration);
        }
    }

    public void setAggregation(AggregationMode mode) {
        if (mode == null || mode == _aggregation.getValue()) return;
        _aggregation.setValue(mode);
        rebuildWeeklySeries();
    }

    public void setSelectedDataset(String dataset) {
        if (dataset == null || dataset.equals(_selectedDataset.getValue())) return;
        _selectedDataset.setValue(dataset);
        recomputeCalcTypesAndBuffers(dataset);
        rebuildWeeklySeries();
    }

    public void setSelectedCalcType(String calcType) {
        if (calcType == null || calcType.equals(_selectedCalcType.getValue())) return;
        _selectedCalcType.setValue(calcType);
        recomputeBuffers(_selectedDataset.getValue(), calcType);
        rebuildWeeklySeries();
    }

    public void setSelectedBuffer(String buffer) {
        if (buffer == null || buffer.equals(_selectedBuffer.getValue())) return;
        _selectedBuffer.setValue(buffer);
        rebuildWeeklySeries();
    }

    private void loadDatesAndFetchWindow(boolean forceRefresh) {
        int gen = ++loadGeneration;
        _isLoading.setValue(true);
        _error.setValue(null);
        datesRepository.getDatesWithTrajectory(ZoneId.systemDefault().getId(), new RepositoryCallback<>() {
            @Override
            public void onSuccess(Map<YearMonthCompositeKey, List<Integer>> result) {
                if (gen != loadGeneration) return;
                cachedTrajectoryDates = result;
                latestRecordedDate = findLatestRecordedDate(result);
                int weeks = nonNull(_weekWindow.getValue(), 12);
                fetchDaysInWindow(weeks, forceRefresh, gen);
            }

            @Override
            public void onFailure(Throwable throwable) {
                if (gen != loadGeneration) return;
                _isLoading.postValue(false);
                _error.postValue("Could not load trajectory dates.");
            }
        });
    }

    private void fetchDaysInWindow(int weeks, boolean forceRefresh, int gen) {
        lastLoadedCalcMs = exposureCachePrefs.getLong("__last_calc_ms", 0L);
        LocalDate anchor = latestRecordedDate != null ? latestRecordedDate : LocalDate.now();
        LocalDate cutoff = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(Math.max(1, weeks) - 1L);

        List<LocalDate> windowDates = new ArrayList<>();
        if (cachedTrajectoryDates != null) {
            for (Map.Entry<YearMonthCompositeKey, List<Integer>> entry : cachedTrajectoryDates.entrySet()) {
                YearMonth ym = YearMonth.of(entry.getKey().year(), entry.getKey().month());
                for (Integer dom : entry.getValue()) {
                    if (dom == null || dom < 1 || dom > ym.lengthOfMonth()) continue;
                    LocalDate d = ym.atDay(dom);
                    if (!d.isBefore(cutoff) && !d.isAfter(anchor)) {
                        windowDates.add(d);
                    }
                }
            }
        }
        windowDates.sort(Comparator.naturalOrder());

        if (windowDates.isEmpty()) {
            loadedDays.clear();
            _datasets.postValue(new ArrayList<>());
            _weekPoints.postValue(new ArrayList<>());
            _isLoading.postValue(false);
            return;
        }

        _isLoading.postValue(true);
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, String> dayJsons = new LinkedHashMap<>();
        Queue<LocalDate> toFetch = new ArrayDeque<>();

        for (LocalDate day : windowDates) {
            boolean isLatest = day.equals(latestRecordedDate);
            String cached = exposureCachePrefs.getString(day.toString(), null);
            if (!forceRefresh && !isLatest && cached != null) {
                dayJsons.put(day, cached);
            } else {
                toFetch.add(day);
            }
        }

        if (toFetch.isEmpty()) {
            parseAndPublish(dayJsons, gen);
            return;
        }

        final int total = toFetch.size();
        final int[] completed = {0};
        int workers = Math.min(MAX_CONCURRENT_REQUESTS, total);
        for (int i = 0; i < workers; i++) {
            fetchNextDay(toFetch, dayJsons, total, completed, zone, gen);
        }
    }

    private void fetchNextDay(Queue<LocalDate> toFetch,
                              Map<LocalDate, String> dayJsons,
                              int total,
                              int[] completed,
                              ZoneId zone,
                              int gen) {
        if (gen != loadGeneration) return;
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
                if (gen != loadGeneration) return;
                String safeJson = result != null ? result : "[]";
                exposureCachePrefs.edit().putString(day.toString(), safeJson).apply();
                synchronized (dayJsons) {
                    dayJsons.put(day, safeJson);
                }
                step();
            }

            @Override
            public void onFailure(Throwable throwable) {
                if (gen != loadGeneration) return;
                step();
            }

            private void step() {
                boolean done;
                synchronized (toFetch) {
                    completed[0]++;
                    done = completed[0] >= total;
                }
                if (done) {
                    parseAndPublish(dayJsons, gen);
                } else {
                    fetchNextDay(toFetch, dayJsons, total, completed, zone, gen);
                }
            }
        });
    }

    private void parseAndPublish(Map<LocalDate, String> dayJsons, int gen) {
        if (gen != loadGeneration) return;
        loadedDays.clear();

        List<LocalDate> sortedDates = new ArrayList<>(dayJsons.keySet());
        sortedDates.sort(Comparator.naturalOrder());

        Set<String> allDatasets = new LinkedHashSet<>();

        for (LocalDate day : sortedDates) {
            String json = dayJsons.get(day);
            if (json == null || json.isBlank()) continue;

            Map<String, DayAccumulator> accMap = new LinkedHashMap<>();
            int tripCount = 0;

            try {
                JSONArray groups = new JSONArray(json);
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    String key = group.optString("key", "");
                    int tripIdx = resolveTripIndex(group, key);
                    if (tripIdx <= 0 && !key.toLowerCase(Locale.ROOT).startsWith("trip")) continue;

                    JSONObject results = group.optJSONObject("results");
                    if (results == null || results.length() == 0) continue;

                    List<DayMetricValue> parsed = parseGroupResults(results);
                    if (parsed.isEmpty()) continue;

                    tripCount++;
                    for (DayMetricValue m : parsed) {
                        allDatasets.add(m.dataset);
                        String accKey = m.dataset + "|" + m.calcType + "|" + m.buffer;
                        DayAccumulator acc = accMap.get(accKey);
                        if (acc == null) {
                            acc = new DayAccumulator(m.dataset, m.calcType, m.buffer, m.isAverage);
                            accMap.put(accKey, acc);
                        }
                        acc.add(m.value, m.unit);
                    }
                }
            } catch (JSONException ignored) {
            }

            if (tripCount > 0 && !accMap.isEmpty()) {
                List<DayMetricValue> metrics = new ArrayList<>(accMap.size());
                for (DayAccumulator acc : accMap.values()) {
                    metrics.add(acc.build());
                }
                loadedDays.add(new DayExposure(day, tripCount, metrics));
            }
        }

        List<String> datasetList = new ArrayList<>(allDatasets);
        _datasets.postValue(datasetList);

        String activeDs = _selectedDataset.getValue();
        if (activeDs == null || !datasetList.contains(activeDs)) {
            activeDs = datasetList.isEmpty() ? null : datasetList.get(0);
            _selectedDataset.postValue(activeDs);
        }
        recomputeCalcTypesAndBuffers(activeDs);
        rebuildWeeklySeries();
        _isLoading.postValue(false);
    }

    private void recomputeCalcTypesAndBuffers(@Nullable String dataset) {
        if (dataset == null) {
            _calcTypes.postValue(Collections.emptyList());
            _selectedCalcType.postValue(null);
            _buffers.postValue(Collections.emptyList());
            _selectedBuffer.postValue(null);
            return;
        }
        Set<String> types = new LinkedHashSet<>();
        for (DayExposure d : loadedDays) {
            for (DayMetricValue m : d.metrics) {
                if (m.dataset.equals(dataset)) types.add(m.calcType);
            }
        }
        List<String> typeList = new ArrayList<>(types);
        _calcTypes.postValue(typeList);

        String activeType = _selectedCalcType.getValue();
        if (activeType == null || !typeList.contains(activeType)) {
            activeType = typeList.isEmpty() ? null : typeList.get(0);
            _selectedCalcType.postValue(activeType);
        }
        recomputeBuffers(dataset, activeType);
    }

    private void recomputeBuffers(@Nullable String dataset, @Nullable String calcType) {
        if (dataset == null || calcType == null) {
            _buffers.postValue(Collections.emptyList());
            _selectedBuffer.postValue(null);
            return;
        }
        Set<String> bufSet = new LinkedHashSet<>();
        for (DayExposure d : loadedDays) {
            for (DayMetricValue m : d.metrics) {
                if (m.dataset.equals(dataset) && m.calcType.equals(calcType)) {
                    bufSet.add(m.buffer);
                }
            }
        }
        List<String> bufList = new ArrayList<>(bufSet);
        bufList.sort(CompareTripsViewModel::compareDistanceKeys);
        _buffers.postValue(bufList);

        String activeBuf = _selectedBuffer.getValue();
        if (activeBuf == null || !bufList.contains(activeBuf)) {
            activeBuf = bufList.isEmpty() ? null : bufList.get(0);
            _selectedBuffer.postValue(activeBuf);
        }
    }

    private void rebuildWeeklySeries() {
        String dataset = _selectedDataset.getValue();
        String calcType = _selectedCalcType.getValue();
        String buffer = _selectedBuffer.getValue();
        AggregationMode mode = _aggregation.getValue() != null ? _aggregation.getValue() : AggregationMode.MEAN;

        if (dataset == null || calcType == null || buffer == null || loadedDays.isEmpty()) {
            _weekPoints.postValue(new ArrayList<>());
            return;
        }

        // Group DayExposure items by ISO week (Monday .. Sunday)
        Map<LocalDate, List<DayExposure>> byWeekMonday = new LinkedHashMap<>();
        for (DayExposure day : loadedDays) {
            LocalDate monday = day.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeekMonday.computeIfAbsent(monday, k -> new ArrayList<>()).add(day);
        }

        List<LocalDate> sortedMondays = new ArrayList<>(byWeekMonday.keySet());
        sortedMondays.sort(Comparator.naturalOrder());

        WeekFields iso = WeekFields.ISO;
        DateTimeFormatter rangeFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        List<WeekPoint> points = new ArrayList<>();

        for (LocalDate monday : sortedMondays) {
            List<DayExposure> weekDays = byWeekMonday.get(monday);
            if (weekDays == null || weekDays.isEmpty()) continue;

            List<Double> vals = new ArrayList<>();
            String unit = "";
            int totalTrips = 0;

            for (DayExposure d : weekDays) {
                totalTrips += d.tripCount;
                DayMetricValue match = d.find(dataset, calcType, buffer);
                if (match != null) {
                    vals.add(match.value);
                    if (unit.isEmpty() && !match.unit.isEmpty()) unit = match.unit;
                }
            }
            if (vals.isEmpty()) continue;

            double sum = 0.0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double v : vals) {
                sum += v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            double mean = sum / vals.size();
            double displayValue = mode == AggregationMode.TOTAL
                    ? sum
                    : (mode == AggregationMode.PEAK ? max : mean);

            int weekNum = monday.get(iso.weekOfWeekBasedYear());
            int weekYear = monday.get(iso.weekBasedYear());
            String weekKey = String.format(Locale.US, "%d-W%02d", weekYear, weekNum);
            String weekLabel = String.format(Locale.US, "W%02d", weekNum);
            LocalDate sunday = monday.plusDays(6);
            String dateRange = monday.format(rangeFmt) + " – " + sunday.format(rangeFmt);

            points.add(new WeekPoint(
                    weekKey, weekLabel, dateRange, monday,
                    displayValue, min, max, unit,
                    vals.size(), totalTrips, weekDays));
        }

        _weekPoints.postValue(points);
    }

    private List<DayMetricValue> parseGroupResults(JSONObject results) {
        List<DayMetricValue> out = new ArrayList<>();
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
                Matcher um = UNIT_BRACKET.matcher(leaf.value);
                if (um.find() && um.group(1) != null && !"-".equals(um.group(1))) {
                    unit = um.group(1).trim();
                }
                String cleaned = UNIT_BRACKET.matcher(leaf.value).replaceAll("").trim();
                try {
                    double num = Double.parseDouble(cleaned);
                    out.add(new DayMetricValue(dataset, calcType, buffer, num, unit, isAverageCalcType(calcType)));
                } catch (NumberFormatException ignored) {
                }
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

    private static boolean isAverageCalcType(String calcType) {
        String lower = calcType.toLowerCase(Locale.ROOT);
        return lower.contains("average") || lower.contains("avg") || lower.contains("mean")
                || lower.contains("ndvi") || lower.contains("gvi") || lower.contains("utci")
                || lower.contains("index") || lower.contains("rate");
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

    @Nullable
    private static LocalDate findLatestRecordedDate(Map<YearMonthCompositeKey, List<Integer>> map) {
        LocalDate latest = null;
        for (Map.Entry<YearMonthCompositeKey, List<Integer>> entry : map.entrySet()) {
            YearMonth ym = YearMonth.of(entry.getKey().year(), entry.getKey().month());
            for (Integer dom : entry.getValue()) {
                if (dom == null || dom < 1 || dom > ym.lengthOfMonth()) continue;
                LocalDate d = ym.atDay(dom);
                if (latest == null || d.isAfter(latest)) latest = d;
            }
        }
        return latest;
    }

    private static int nonNull(@Nullable Integer v, int fallback) {
        return v == null ? fallback : v;
    }
}