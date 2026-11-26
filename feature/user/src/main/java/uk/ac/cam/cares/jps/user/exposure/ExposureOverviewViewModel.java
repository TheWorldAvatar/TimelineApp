package uk.ac.cam.cares.jps.user.exposure;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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
import uk.ac.cam.cares.jps.data.ExposureCalculationAgentRepository;
import uk.ac.cam.cares.jps.data.ExposureFeatureInfoRepository;
import uk.ac.cam.cares.jps.data.TripAgentRepository;
import uk.ac.cam.cares.jps.model.YearMonthCompositeKey;
import uk.ac.cam.cares.jps.utils.RepositoryCallback;

@HiltViewModel
public class ExposureOverviewViewModel extends ViewModel {

    public enum DayStatus { CALCULATED, RUNNING, MISSING }
    public enum Filter { ALL, CALCULATED, MISSING }

    public static final class ConfigSummary {
        public final String dataset;
        public final String calcType;
        public final List<String> buffers;
        public final int segmentCount;

        ConfigSummary(String dataset, String calcType, List<String> buffers, int segmentCount) {
            this.dataset = dataset;
            this.calcType = calcType;
            this.buffers = buffers;
            this.segmentCount = segmentCount;
        }
    }

    public static final class DaySummary {
        public final LocalDate date;
        public final DayStatus status;
        public final int tripOrStayCount;
        public final List<ConfigSummary> configs;

        DaySummary(LocalDate date, DayStatus status, int tripOrStayCount, List<ConfigSummary> configs) {
            this.date = date;
            this.status = status;
            this.tripOrStayCount = tripOrStayCount;
            this.configs = configs;
        }
    }

    private static final String CACHE_PREFS_NAME = "exposure_overview_cache";
    private static final String COMPARE_CACHE_PREFS_NAME = "compare_trips_cache";
    private static final String KEY_LAST_CALC_MS = "__last_calc_ms";
    private static final int MAX_CONCURRENT_DAY_REQUESTS = 3;

    private static final Pattern STRICT_DISTANCE_KEY =
            Pattern.compile("^\\s*\\d+(?:\\.\\d+)?\\s*(?:km|m)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_VALUE =
            Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(km|m)\\s*$", Pattern.CASE_INSENSITIVE);

    private final DatesWithTrajectoryRepository datesRepository;
    private final ExposureFeatureInfoRepository exposureInfoRepository;
    private final TripAgentRepository tripAgentRepository;
    private final ExposureCalculationAgentRepository exposureCalcRepository;
    private final SharedPreferences cachePrefs;
    private final SharedPreferences compareCachePrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<YearMonth> _selectedMonth = new MutableLiveData<>(YearMonth.now());
    private final MutableLiveData<Filter> _filter = new MutableLiveData<>(Filter.ALL);
    private final MutableLiveData<Boolean> _isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> _error = new MutableLiveData<>();
    private final MutableLiveData<String> _freshnessLabel = new MutableLiveData<>("");
    private final MutableLiveData<List<DaySummary>> _allDays = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<DaySummary>> _visibleDays = new MutableLiveData<>(new ArrayList<>());

    public final LiveData<YearMonth> selectedMonth = _selectedMonth;
    public final LiveData<Filter> filter = _filter;
    public final LiveData<Boolean> isLoading = _isLoading;
    public final LiveData<String> error = _error;
    public final LiveData<String> freshnessLabel = _freshnessLabel;
    public final LiveData<List<DaySummary>> allDays = _allDays;
    public final LiveData<List<DaySummary>> visibleDays = _visibleDays;

    private Map<YearMonthCompositeKey, List<Integer>> cachedTrajectoryDates;
    private LocalDate latestRecordedDate;
    private final Set<LocalDate> runningDates = Collections.synchronizedSet(new HashSet<>());
    private int loadGeneration = 0;
    private long lastLoadedCalcMs = 0L;
    private boolean initialized = false;

    @Inject
    public ExposureOverviewViewModel(
            DatesWithTrajectoryRepository datesRepository,
            ExposureFeatureInfoRepository exposureInfoRepository,
            TripAgentRepository tripAgentRepository,
            ExposureCalculationAgentRepository exposureCalcRepository,
            @ApplicationContext Context context) {
        this.datesRepository = datesRepository;
        this.exposureInfoRepository = exposureInfoRepository;
        this.tripAgentRepository = tripAgentRepository;
        this.exposureCalcRepository = exposureCalcRepository;
        this.cachePrefs = context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE);
        this.compareCachePrefs = context.getSharedPreferences(COMPARE_CACHE_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void loadMonthIfNeeded() {
        if (!initialized) {
            initialized = true;
            YearMonth current = _selectedMonth.getValue();
            loadMonth(current != null ? current : YearMonth.now(), false);
        }
    }

    public void loadMonth(YearMonth month) {
        loadMonth(month, false);
    }

    public void refreshCurrentMonth() {
        YearMonth current = _selectedMonth.getValue();
        if (current != null) {
            loadMonth(current, true);
        }
    }

    public void refreshIfInvalidated() {
        if (!initialized) return;
        YearMonth current = _selectedMonth.getValue();
        if (current == null) return;
        long latestCalcMs = cachePrefs.getLong(KEY_LAST_CALC_MS, 0L);
        if (latestCalcMs > lastLoadedCalcMs) {
            loadMonth(current, false);
        }
    }

    public void previousMonth() {
        YearMonth current = _selectedMonth.getValue();
        if (current != null) {
            loadMonth(current.minusMonths(1), false);
        }
    }

    public void nextMonth() {
        YearMonth current = _selectedMonth.getValue();
        if (current != null && current.isBefore(YearMonth.now())) {
            loadMonth(current.plusMonths(1), false);
        }
    }

    public void setFilter(Filter newFilter) {
        if (newFilter == null) return;
        _filter.setValue(newFilter);
        applyFilter(_allDays.getValue(), newFilter);
    }

    public void triggerCalculationForDay(LocalDate date) {
        if (date == null || runningDates.contains(date)) return;
        String previousSignature = buildDaySignature(date);
        runningDates.add(date);
        updateSingleDayStatus(date, DayStatus.RUNNING);

        ZoneId zone = ZoneId.systemDefault();
        long lowerbound = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long upperbound = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        int generation = loadGeneration;

        tripAgentRepository.runTripAgent(lowerbound, upperbound, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String tripResult) {
                exposureCalcRepository.triggerCalculation(lowerbound, upperbound, new RepositoryCallback<>() {
                    @Override
                    public void onSuccess(String result) {
                        runningDates.remove(date);
                        cachePrefs.edit().remove(date.toString()).apply();
                        compareCachePrefs.edit()
                                .remove(date.toString())
                                .putLong(KEY_LAST_CALC_MS, System.currentTimeMillis())
                                .apply();
                        // Allow KG/feature-info endpoint to settle before querying updated results
                        mainHandler.postDelayed(() ->
                                refreshSingleDay(date, generation, previousSignature, true), 1500L);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        runningDates.remove(date);
                        _error.postValue("Calculation failed for " + date + ".");
                        refreshSingleDay(date, generation, previousSignature, false);
                    }
                });
            }

            @Override
            public void onFailure(Throwable throwable) {
                runningDates.remove(date);
                _error.postValue("Trip detection failed for " + date + ".");
                refreshSingleDay(date, generation, previousSignature, false);
            }
        });
    }

    private void loadMonth(YearMonth month, boolean forceRefresh) {
        int generation = ++loadGeneration;
        lastLoadedCalcMs = cachePrefs.getLong(KEY_LAST_CALC_MS, 0L);
        _selectedMonth.setValue(month);
        _isLoading.setValue(true);
        _error.setValue(null);

        if (cachedTrajectoryDates == null || forceRefresh) {
            datesRepository.getDatesWithTrajectory(ZoneId.systemDefault().getId(), new RepositoryCallback<>() {
                @Override
                public void onSuccess(Map<YearMonthCompositeKey, List<Integer>> result) {
                    if (generation != loadGeneration) return;
                    cachedTrajectoryDates = result;
                    latestRecordedDate = findLatestRecordedDate(result);
                    fetchMonthDays(month, forceRefresh, generation);
                }

                @Override
                public void onFailure(Throwable throwable) {
                    if (generation != loadGeneration) return;
                    _isLoading.postValue(false);
                    _error.postValue("Could not load trajectory dates.");
                }
            });
        } else {
            fetchMonthDays(month, forceRefresh, generation);
        }
    }

    private void fetchMonthDays(YearMonth month, boolean forceRefresh, int generation) {
        if (generation != loadGeneration) return;

        List<LocalDate> daysWithData = getSortedDatesInMonth(month);
        if (daysWithData.isEmpty()) {
            _allDays.postValue(new ArrayList<>());
            _visibleDays.postValue(new ArrayList<>());
            _freshnessLabel.postValue("No recorded trips in this month");
            _isLoading.postValue(false);
            return;
        }

        Map<LocalDate, DaySummary> dayMap = new LinkedHashMap<>();
        Queue<LocalDate> toFetch = new ArrayDeque<>();
        int cachedHits = 0;

        for (LocalDate day : daysWithData) {
            if (runningDates.contains(day)) {
                DaySummary existing = readCachedDay(day);
                dayMap.put(day, new DaySummary(
                        day,
                        DayStatus.RUNNING,
                        existing != null ? existing.tripOrStayCount : 0,
                        existing != null ? existing.configs : new ArrayList<>()));
                continue;
            }

            boolean isLatestDay = day.equals(latestRecordedDate);
            DaySummary cached = readCachedDay(day);
            if (!forceRefresh && !isLatestDay && cached != null) {
                dayMap.put(day, cached);
                cachedHits++;
            } else {
                toFetch.add(day);
            }
        }

        if (toFetch.isEmpty()) {
            publishDayMap(dayMap, generation, forceRefresh, cachedHits, 0);
            return;
        }

        final int totalToFetch = toFetch.size();
        final int[] completed = {0};
        final int[] failed = {0};
        final int finalCachedHits = cachedHits;
        ZoneId zone = ZoneId.systemDefault();

        int initialWorkers = Math.min(MAX_CONCURRENT_DAY_REQUESTS, totalToFetch);
        for (int i = 0; i < initialWorkers; i++) {
            fetchNextDayInQueue(toFetch, dayMap, totalToFetch, completed, failed,
                    finalCachedHits, forceRefresh, zone, generation);
        }
    }

    private void fetchNextDayInQueue(
            Queue<LocalDate> toFetch,
            Map<LocalDate, DaySummary> dayMap,
            int totalToFetch,
            int[] completed,
            int[] failed,
            int cachedHits,
            boolean forceRefresh,
            ZoneId zone,
            int generation) {
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
                DaySummary parsed = parseDayResults(day, result);
                writeCachedDay(parsed);
                synchronized (dayMap) {
                    dayMap.put(day, parsed);
                }
                onOneDone(false);
            }

            @Override
            public void onFailure(Throwable throwable) {
                if (generation != loadGeneration) return;
                DaySummary fallback = readCachedDay(day);
                synchronized (dayMap) {
                    if (fallback != null) {
                        dayMap.put(day, fallback);
                    } else {
                        dayMap.put(day, new DaySummary(day, DayStatus.MISSING, 0, new ArrayList<>()));
                    }
                }
                onOneDone(true);
            }

            private void onOneDone(boolean isFailure) {
                boolean allDone;
                synchronized (toFetch) {
                    if (isFailure) failed[0]++;
                    completed[0]++;
                    allDone = completed[0] >= totalToFetch;
                }
                if (allDone) {
                    if (failed[0] == totalToFetch && cachedHits == 0) {
                        _error.postValue("Could not load calculated exposures.");
                    }
                    publishDayMap(dayMap, generation, forceRefresh, cachedHits, totalToFetch - failed[0]);
                } else {
                    fetchNextDayInQueue(toFetch, dayMap, totalToFetch, completed, failed,
                            cachedHits, forceRefresh, zone, generation);
                }
            }
        });
    }

    private void refreshSingleDay(LocalDate day, int expectedGeneration,
                                  String previousSignature, boolean retryOnceIfUnchanged) {
        if (expectedGeneration != loadGeneration) return;
        ZoneId zone = ZoneId.systemDefault();
        long lb = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long ub = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;

        exposureInfoRepository.getTimelineResults(lb, ub, new RepositoryCallback<>() {
            @Override
            public void onSuccess(String result) {
                if (expectedGeneration != loadGeneration) return;
                DaySummary updated = parseDayResults(day, result);
                writeCachedDay(updated);
                replaceDayInLiveList(updated);

                if (retryOnceIfUnchanged && buildDaySignature(updated).equals(previousSignature)) {
                    mainHandler.postDelayed(() ->
                            refreshSingleDay(day, expectedGeneration, previousSignature, false), 3000L);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                if (expectedGeneration != loadGeneration) return;
                DaySummary cached = readCachedDay(day);
                if (cached != null) {
                    replaceDayInLiveList(cached);
                } else {
                    updateSingleDayStatus(day, DayStatus.MISSING);
                }
            }
        });
    }

    private String buildDaySignature(LocalDate date) {
        List<DaySummary> current = _allDays.getValue();
        if (current != null) {
            for (DaySummary d : current) {
                if (d.date.equals(date)) return buildDaySignature(d);
            }
        }
        return "";
    }

    private String buildDaySignature(DaySummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append(summary.status.name()).append('|').append(summary.tripOrStayCount);
        for (ConfigSummary c : summary.configs) {
            sb.append('|').append(c.dataset).append(':').append(c.calcType).append(':').append(c.buffers);
        }
        return sb.toString();
    }

    private void publishDayMap(Map<LocalDate, DaySummary> dayMap,
                               int generation,
                               boolean forceRefresh,
                               int cachedHits,
                               int fetchedCount) {
        if (generation != loadGeneration) return;

        List<DaySummary> sorted = new ArrayList<>(dayMap.values());
        sorted.sort((a, b) -> b.date.compareTo(a.date));

        String freshness;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        if (forceRefresh) {
            freshness = "Refreshed all " + sorted.size() + " days";
        } else if (fetchedCount > 0 && cachedHits > 0) {
            String latestNote = latestRecordedDate != null ? latestRecordedDate.format(fmt) : "latest";
            freshness = "Checked " + latestNote + " · " + cachedHits + " days from cache";
        } else if (cachedHits > 0) {
            freshness = "Loaded " + cachedHits + " days from cache · tap ↻ to re-check";
        } else {
            freshness = "Checked " + sorted.size() + " days";
        }

        _freshnessLabel.postValue(freshness);
        _allDays.postValue(sorted);
        applyFilter(sorted, _filter.getValue());
        _isLoading.postValue(false);
    }

    private void updateSingleDayStatus(LocalDate date, DayStatus status) {
        List<DaySummary> current = _allDays.getValue();
        if (current == null) return;
        List<DaySummary> next = new ArrayList<>(current.size());
        for (DaySummary item : current) {
            if (item.date.equals(date)) {
                next.add(new DaySummary(item.date, status, item.tripOrStayCount, item.configs));
            } else {
                next.add(item);
            }
        }
        _allDays.postValue(next);
        applyFilter(next, _filter.getValue());
    }

    private void replaceDayInLiveList(DaySummary replacement) {
        List<DaySummary> current = _allDays.getValue();
        if (current == null) return;
        List<DaySummary> next = new ArrayList<>(current.size());
        for (DaySummary item : current) {
            next.add(item.date.equals(replacement.date) ? replacement : item);
        }
        _allDays.postValue(next);
        applyFilter(next, _filter.getValue());
    }

    private void applyFilter(@Nullable List<DaySummary> all, @Nullable Filter activeFilter) {
        if (all == null) {
            _visibleDays.postValue(new ArrayList<>());
            return;
        }
        Filter effective = activeFilter != null ? activeFilter : Filter.ALL;
        if (effective == Filter.ALL) {
            _visibleDays.postValue(new ArrayList<>(all));
            return;
        }
        List<DaySummary> filtered = new ArrayList<>();
        for (DaySummary item : all) {
            if (effective == Filter.CALCULATED && item.status == DayStatus.CALCULATED) {
                filtered.add(item);
            } else if (effective == Filter.MISSING && item.status != DayStatus.CALCULATED) {
                filtered.add(item);
            }
        }
        _visibleDays.postValue(filtered);
    }

    private DaySummary parseDayResults(LocalDate day, @Nullable String json) {
        if (runningDates.contains(day)) {
            return new DaySummary(day, DayStatus.RUNNING, 0, new ArrayList<>());
        }
        if (json == null || json.isBlank()) {
            return new DaySummary(day, DayStatus.MISSING, 0, new ArrayList<>());
        }

        Map<String, Set<String>> buffersByConfig = new LinkedHashMap<>();
        Map<String, Integer> countByConfig = new LinkedHashMap<>();
        int groupsWithResults = 0;

        try {
            JSONArray groups = new JSONArray(json);
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                JSONObject results = group.optJSONObject("results");
                if (results == null || results.length() == 0) continue;

                groupsWithResults++;
                Set<String> seenInThisGroup = new HashSet<>();

                Iterator<String> datasetKeys = results.keys();
                while (datasetKeys.hasNext()) {
                    String dataset = datasetKeys.next();
                    JSONObject root = results.optJSONObject(dataset);
                    if (root == null) continue;

                    List<List<String>> leafPaths = new ArrayList<>();
                    collectLeafPaths(root, new ArrayList<>(), leafPaths);

                    for (List<String> path : leafPaths) {
                        int distanceIdx = -1;
                        for (int k = 0; k < path.size(); k++) {
                            String part = path.get(k);
                            if ("-".equals(part) || STRICT_DISTANCE_KEY.matcher(part).matches()) {
                                distanceIdx = k;
                                break;
                            }
                        }

                        String calcType;
                        String buffer;
                        if (distanceIdx < 0) {
                            calcType = String.join(" · ", path);
                            buffer = "Direct";
                        } else {
                            calcType = String.join(" · ", path.subList(0, distanceIdx));
                            String rawBuf = path.get(distanceIdx).trim();
                            buffer = "-".equals(rawBuf) ? "Direct" : rawBuf;
                        }
                        if (calcType.isEmpty()) calcType = dataset;

                        String configKey = dataset + "|" + calcType;
                        buffersByConfig.computeIfAbsent(configKey, x -> new LinkedHashSet<>()).add(buffer);
                        if (seenInThisGroup.add(configKey)) {
                            countByConfig.put(configKey, countByConfig.getOrDefault(configKey, 0) + 1);
                        }
                    }
                }
            }
        } catch (JSONException ignored) {
        }

        if (groupsWithResults == 0 || buffersByConfig.isEmpty()) {
            return new DaySummary(day, DayStatus.MISSING, 0, new ArrayList<>());
        }

        List<ConfigSummary> configs = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : buffersByConfig.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String dataset = parts[0];
            String calcType = parts.length > 1 ? parts[1] : dataset;
            List<String> sortedBuffers = new ArrayList<>(entry.getValue());
            sortedBuffers.sort(ExposureOverviewViewModel::compareDistanceKeys);
            int segCount = countByConfig.getOrDefault(entry.getKey(), groupsWithResults);
            configs.add(new ConfigSummary(dataset, calcType, sortedBuffers, segCount));
        }

        return new DaySummary(day, DayStatus.CALCULATED, groupsWithResults, configs);
    }

    private void collectLeafPaths(JSONObject node, List<String> path, List<List<String>> out) {
        Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("collapse".equals(key) || "display_order".equals(key)) continue;
            Object child = node.opt(key);
            if (child == null || child == JSONObject.NULL || child instanceof JSONArray) continue;

            List<String> next = new ArrayList<>(path);
            next.add(key);
            if (child instanceof JSONObject) {
                collectLeafPaths((JSONObject) child, next, out);
            } else {
                out.add(next);
            }
        }
    }

    private void writeCachedDay(DaySummary summary) {
        if (summary.status == DayStatus.RUNNING) return;
        try {
            JSONObject root = new JSONObject();
            root.put("status", summary.status.name());
            root.put("count", summary.tripOrStayCount);
            JSONArray cfgArr = new JSONArray();
            for (ConfigSummary cfg : summary.configs) {
                JSONObject obj = new JSONObject();
                obj.put("dataset", cfg.dataset);
                obj.put("calcType", cfg.calcType);
                obj.put("segmentCount", cfg.segmentCount);
                JSONArray bufArr = new JSONArray();
                for (String b : cfg.buffers) bufArr.put(b);
                obj.put("buffers", bufArr);
                cfgArr.put(obj);
            }
            root.put("configs", cfgArr);
            cachePrefs.edit().putString(summary.date.toString(), root.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    @Nullable
    private DaySummary readCachedDay(LocalDate day) {
        String raw = cachePrefs.getString(day.toString(), null);
        if (raw == null) return null;
        try {
            JSONObject root = new JSONObject(raw);
            DayStatus status = DayStatus.valueOf(root.optString("status", DayStatus.MISSING.name()));
            int count = root.optInt("count", 0);
            JSONArray cfgArr = root.optJSONArray("configs");
            List<ConfigSummary> configs = new ArrayList<>();
            if (cfgArr != null) {
                for (int i = 0; i < cfgArr.length(); i++) {
                    JSONObject obj = cfgArr.optJSONObject(i);
                    if (obj == null) continue;
                    List<String> buffers = new ArrayList<>();
                    JSONArray bufArr = obj.optJSONArray("buffers");
                    if (bufArr != null) {
                        for (int j = 0; j < bufArr.length(); j++) {
                            buffers.add(bufArr.optString(j));
                        }
                    }
                    configs.add(new ConfigSummary(
                            obj.optString("dataset"),
                            obj.optString("calcType"),
                            buffers,
                            obj.optInt("segmentCount", count)));
                }
            }
            return new DaySummary(day, status, count, configs);
        } catch (Exception e) {
            return null;
        }
    }

    private List<LocalDate> getSortedDatesInMonth(YearMonth month) {
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

    private static double distanceInMetres(String key) {
        Matcher m = DISTANCE_VALUE.matcher(key);
        if (!m.find()) return Double.MAX_VALUE;
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        return (unit != null && unit.equalsIgnoreCase("km")) ? value * 1000.0 : value;
    }

    private static int compareDistanceKeys(String a, String b) {
        int cmp = Double.compare(distanceInMetres(a), distanceInMetres(b));
        return cmp != 0 ? cmp : a.compareToIgnoreCase(b);
    }
}