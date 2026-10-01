package com.beacon.testapp

import android.app.Application
import android.app.Notification
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.RangeNotifier
import org.altbeacon.beacon.Region
import org.json.JSONArray
import org.json.JSONObject

class BeaconTracker private constructor(
    private val app: Application,
) : RangeNotifier, MonitorNotifier {
    private val beaconManager = BeaconManager.getInstanceForApplication(app)
    private val region = Region(
        MAJOR_100_REGION,
        null,
        Identifier.fromInt(TARGET_MAJOR),
        null,
    )
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val closestApproachById = mutableMapOf<String, ApproachSession>()
    private val approachHistory = mutableListOf<ClosestApproach>()
    private val floorOverrides = mutableMapOf<String, String>()
    private val started = AtomicBoolean(false)
    private val foregroundScanEnabled = AtomicBoolean(false)
    private val appForeground = AtomicBoolean(false)
    private val insideRegion = AtomicBoolean(false)
    private val measuring = AtomicBoolean(false)
    private val stateLock = Any()

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    init {
        restore()
    }

    fun start() {
        runCatching {
            beaconManager.setEnableScheduledScanJobs(false)
            if (started.compareAndSet(false, true)) {
                beaconManager.addMonitorNotifier(this)
                beaconManager.addRangeNotifier(this)
            }
            beaconManager.startMonitoring(region)
            beaconManager.startRangingBeacons(region)
            syncMeasuring()
            _uiState.update { it.copy(isScanning = true, errorMessage = null) }
        }.onFailure { error ->
            started.set(false)
            _uiState.update {
                it.copy(errorMessage = error.message ?: "비콘 스캔을 시작하지 못했습니다.")
            }
        }
    }

    fun enableForegroundScanning(notification: Notification, notificationId: Int) {
        if (foregroundScanEnabled.get()) return
        runCatching {
            beaconManager.setEnableScheduledScanJobs(false)
            beaconManager.enableForegroundServiceScanning(notification, notificationId)
            foregroundScanEnabled.set(true)
        }
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground.set(foreground)
        if (_uiState.value.isScanning || started.get()) {
            syncMeasuring()
        }
    }

    override fun didEnterRegion(region: Region?) {
        insideRegion.set(true)
        syncMeasuring()
    }

    override fun didExitRegion(region: Region?) {
        insideRegion.set(false)
        syncMeasuring()
    }

    override fun didDetermineStateForRegion(state: Int, region: Region?) {
        insideRegion.set(state == MonitorNotifier.INSIDE)
        syncMeasuring()
    }

    private fun wantsMeasuring(): Boolean = appForeground.get() || insideRegion.get()

    private fun syncMeasuring() {
        val want = wantsMeasuring()
        synchronized(stateLock) {
            applyScanPeriods(want)
            beaconManager.startRangingBeacons(region)
            val changed = measuring.getAndSet(want) != want
            _uiState.update { it.copy(isScanning = true, isMeasuring = want, errorMessage = null) }
            if (changed) {
                BeaconScanService.onMeasuringChanged(want)
            }
        }
    }

    private fun applyScanPeriods(active: Boolean) {
        val between = if (active) ACTIVE_BETWEEN_MS else IDLE_BETWEEN_MS
        beaconManager.foregroundScanPeriod = SCAN_MS
        beaconManager.foregroundBetweenScanPeriod = between
        beaconManager.backgroundScanPeriod = SCAN_MS
        beaconManager.backgroundBetweenScanPeriod = between
        runCatching { beaconManager.updateScanPeriods() }
    }

    override fun didRangeBeaconsInRegion(beacons: MutableCollection<Beacon>?, region: Region?) {
        val now = System.currentTimeMillis()
        val detectedRaw = beacons
            .orEmpty()
            .map(DetectedBeacon::from)
            .filter { it.major.toIntOrNull() == TARGET_MAJOR }
            .sortedByDescending { it.rssi }
        val measuringRaw = detectedRaw.filter { it.isInMeasureRange() }.closestOrNull()
        var selectedFloor = _uiState.value.selectedFloor
        if (measuringRaw != null) {
            synchronized(floorOverrides) { floorOverrides.remove(measuringRaw.id) }
            BeaconDisplayFormat.floorCodeFromName(measuringRaw.name)?.let { selectedFloor = it }
        }
        val detected = detectedRaw.map(::withFloorOverride)
        val inRange = detected.filter { it.isInMeasureRange() }

        synchronized(closestApproachById) {
            val seenIds = mutableSetOf<String>()
            detected.forEach { beacon ->
                val distance = beacon.distanceMeters
                if (!distance.isFinite() || distance < 0.0) return@forEach
                seenIds += beacon.id
                closestApproachById[beacon.id] = nextSession(
                    previous = closestApproachById[beacon.id],
                    beacon = beacon,
                    distance = distance,
                    now = now,
                )
            }
            closestApproachById.keys.toList().forEach { id ->
                if (id in seenIds) return@forEach
                val previous = closestApproachById[id] ?: return@forEach
                val missed = previous.missedScans + 1
                closestApproachById[id] = previous.copy(
                    inside = if (missed >= LEFT_ZONE_MISS_COUNT) false else previous.inside,
                    missedScans = missed,
                )
            }
        }

        val measuringNow = inRange.closestOrNull()
        if (detected.isNotEmpty()) {
            insideRegion.set(true)
        }
        _uiState.update { previous ->
            val beaconToShow = if (measuringNow != null) {
                withFloorOverride(measuringNow)
            } else {
                previous.closestBeacon?.let(::withFloorOverride)
            }
            val closestApproach = beaconToShow?.let { beacon ->
                synchronized(closestApproachById) { closestApproachById[beacon.id]?.record }
            } ?: previous.closestApproach
            previous.copy(
                isScanning = true,
                isMeasuring = measuring.get(),
                beacons = detected,
                closestBeacon = beaconToShow,
                closestApproach = closestApproach,
                approachHistory = historySnapshot(),
                pingSeq = if (inRange.isNotEmpty()) previous.pingSeq + 1 else previous.pingSeq,
                lastUpdateEpochMs = now,
                isInMeasureRange = measuringNow != null,
                selectedFloor = selectedFloor,
                errorMessage = null,
            )
        }
        persist()
        ClosestBeaconWidgetProvider.updateAll(app, _uiState.value)
    }

    fun setFloorOverride(code: String) {
        if (!BeaconDisplayFormat.isFloorCode(code)) return
        val floorLabel = BeaconDisplayFormat.floorLabelFromCode(code) ?: return
        val beacon = _uiState.value.closestBeacon
        if (beacon != null) {
            synchronized(floorOverrides) {
                floorOverrides[beacon.id] = code
            }
            synchronized(closestApproachById) {
                closestApproachById[beacon.id]?.let { session ->
                    closestApproachById[beacon.id] = session.copy(
                        record = session.record?.copy(floorLabel = floorLabel),
                    )
                }
            }
            synchronized(approachHistory) {
                val index = approachHistory.indexOfFirst { it.beaconId == beacon.id }
                if (index >= 0) {
                    approachHistory[index] = approachHistory[index].copy(floorLabel = floorLabel)
                    sortHistoryByName()
                }
            }
        }
        _uiState.update { previous ->
            previous.copy(
                selectedFloor = code,
                closestBeacon = previous.closestBeacon?.let { current ->
                    if (beacon != null && current.id == beacon.id) {
                        current.copy(floorOverride = code)
                    } else {
                        current
                    }
                },
                beacons = previous.beacons.map { current ->
                    if (beacon != null && current.id == beacon.id) {
                        current.copy(floorOverride = code)
                    } else {
                        current
                    }
                },
                closestApproach = previous.closestApproach?.let { current ->
                    if (beacon != null && current.beaconId == beacon.id) {
                        current.copy(floorLabel = floorLabel)
                    } else {
                        current
                    }
                },
                approachHistory = historySnapshot(),
            )
        }
        persist()
        ClosestBeaconWidgetProvider.updateAll(app, _uiState.value)
    }

    private fun withFloorOverride(beacon: DetectedBeacon): DetectedBeacon {
        val code = synchronized(floorOverrides) { floorOverrides[beacon.id] }
        return if (code == null) beacon else beacon.copy(floorOverride = code)
    }

    private fun nextSession(
        previous: ApproachSession?,
        beacon: DetectedBeacon,
        distance: Double,
        now: Long,
    ): ApproachSession {
        val inside = distance <= MEASURE_ZONE_METERS
        val wasInside = previous?.inside == true
        val floorLabel = BeaconDisplayFormat.floorLabel(beacon)
        val record = when {
            inside && !wasInside -> {
                val created = ClosestApproach(
                    name = beacon.name,
                    distanceMeters = distance,
                    epochMs = now,
                    visitId = "${beacon.id}|$now",
                    beaconId = beacon.id,
                    floorLabel = floorLabel,
                )
                upsertHistory(created)
                created
            }
            inside && previous?.record != null && distance < previous.record.distanceMeters -> {
                val updated = previous.record.copy(
                    name = beacon.name,
                    distanceMeters = distance,
                    epochMs = now,
                    floorLabel = floorLabel,
                )
                upsertHistory(updated)
                updated
            }
            inside -> previous?.record ?: ClosestApproach(
                name = beacon.name,
                distanceMeters = distance,
                epochMs = now,
                visitId = "${beacon.id}|$now",
                beaconId = beacon.id,
                floorLabel = floorLabel,
            ).also(::upsertHistory)
            else -> previous?.record
        }
        return ApproachSession(inside = inside, record = record, missedScans = 0)
    }

    private fun upsertHistory(record: ClosestApproach) {
        synchronized(approachHistory) {
            approachHistory.removeAll { item ->
                (record.beaconId.isNotBlank() && item.beaconId == record.beaconId) ||
                    (record.visitId.isNotBlank() && item.visitId == record.visitId)
            }
            approachHistory.add(0, record)
            sortHistoryByName()
            while (approachHistory.size > MAX_HISTORY) {
                approachHistory.removeAt(approachHistory.lastIndex)
            }
        }
    }

    private fun historySnapshot(): List<ClosestApproach> {
        return synchronized(approachHistory) {
            sortHistoryByName()
            approachHistory.toList()
        }
    }

    private fun sortHistoryByName() {
        approachHistory.sortWith(
            compareBy<ClosestApproach> { it.name.lowercase() }
                .thenBy { it.beaconId.lowercase() },
        )
    }

    private fun collapseHistoryByBeacon() {
        synchronized(approachHistory) {
            val latestByBeacon = linkedMapOf<String, ClosestApproach>()
            approachHistory.sortedByDescending { it.epochMs }.forEach { item ->
                val key = item.beaconId.ifBlank { item.name }
                if (key.isBlank() || key in latestByBeacon) return@forEach
                latestByBeacon[key] = item
            }
            approachHistory.clear()
            approachHistory.addAll(latestByBeacon.values)
            sortHistoryByName()
        }
    }

    private fun persist() {
        val state = _uiState.value
        val records = synchronized(closestApproachById) {
            JSONArray().also { array ->
                closestApproachById.forEach { (id, session) ->
                    array.put(
                        JSONObject()
                            .put("id", id)
                            .put("name", session.record?.name ?: "")
                            .put("distanceMeters", session.record?.distanceMeters ?: JSONObject.NULL)
                            .put("epochMs", session.record?.epochMs ?: JSONObject.NULL)
                            .put("visitId", session.record?.visitId ?: "")
                            .put("beaconId", session.record?.beaconId ?: id)
                            .put("floorLabel", session.record?.floorLabel ?: "")
                            .put("inside", session.inside),
                    )
                }
            }
        }
        val overrides = synchronized(floorOverrides) {
            JSONObject().also { obj ->
                floorOverrides.forEach { (id, code) -> obj.put(id, code) }
            }
        }
        prefs.edit()
            .putString(KEY_RECORDS, records.toString())
            .putString(KEY_CLOSEST_ID, state.closestBeacon?.id)
            .putString(KEY_CLOSEST_NAME, state.closestBeacon?.name)
            .putString(KEY_SELECTED_FLOOR, state.selectedFloor)
            .putString(KEY_FLOOR_OVERRIDES, overrides.toString())
            .putString(KEY_HISTORY, historyJson())
            .apply()
    }

    private fun historyJson(): String {
        return JSONArray().also { array ->
            historySnapshot().forEach { item ->
                array.put(
                    JSONObject()
                        .put("name", item.name)
                        .put("distanceMeters", item.distanceMeters)
                        .put("epochMs", item.epochMs)
                        .put("visitId", item.visitId)
                        .put("beaconId", item.beaconId)
                        .put("floorLabel", item.floorLabel),
                )
            }
        }.toString()
    }

    private fun restore() {
        restoreOverrides()
        restoreHistory()
        val recordsJson = prefs.getString(KEY_RECORDS, null)
        if (recordsJson != null) {
            runCatching {
                val array = JSONArray(recordsJson)
                synchronized(closestApproachById) {
                    closestApproachById.clear()
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val hasRecord = item.has("distanceMeters") && !item.isNull("distanceMeters")
                        closestApproachById[item.getString("id")] = ApproachSession(
                            inside = item.optBoolean("inside", false),
                            record = if (hasRecord) {
                                val beaconId = item.optString("beaconId").ifBlank { item.getString("id") }
                                val name = item.getString("name")
                                ClosestApproach(
                                    name = name,
                                    distanceMeters = item.getDouble("distanceMeters"),
                                    epochMs = item.getLong("epochMs"),
                                    visitId = item.optString("visitId"),
                                    beaconId = beaconId,
                                    floorLabel = item.optString("floorLabel").ifBlank {
                                        BeaconDisplayFormat.floorLabel(name)
                                    },
                                )
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        applyRestoredParkedState()
    }

    private fun applyRestoredParkedState() {
        val history = historySnapshot()
        val parked = history.maxByOrNull { it.epochMs }
        val closestId = parked?.let { it.beaconId.ifBlank { it.name } }?.ifBlank { null }
            ?: prefs.getString(KEY_CLOSEST_ID, null)
        val closestName = parked?.name?.ifBlank { null }
            ?: prefs.getString(KEY_CLOSEST_NAME, null)
        val approach = parked
            ?: closestId?.let { synchronized(closestApproachById) { closestApproachById[it]?.record } }
        val restoredBeacon = if (closestId != null && closestName != null) {
            withFloorOverride(DetectedBeacon.placeholder(closestId, closestName))
        } else {
            null
        }
        val selectedFloor = prefs.getString(KEY_SELECTED_FLOOR, null)
            ?.takeIf { BeaconDisplayFormat.isFloorCode(it) }
            ?: restoredBeacon?.let { BeaconDisplayFormat.floorCode(it) }
        _uiState.update {
            it.copy(
                closestBeacon = restoredBeacon,
                closestApproach = approach,
                approachHistory = history,
                isInMeasureRange = false,
                selectedFloor = selectedFloor,
            )
        }
        ClosestBeaconWidgetProvider.updateAll(app, _uiState.value)
    }

    private fun restoreHistory() {
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        runCatching {
            val array = JSONArray(json)
            synchronized(approachHistory) {
                approachHistory.clear()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val name = item.optString("name")
                    approachHistory += ClosestApproach(
                        name = name,
                        distanceMeters = item.getDouble("distanceMeters"),
                        epochMs = item.getLong("epochMs"),
                        visitId = item.optString("visitId"),
                        beaconId = item.optString("beaconId"),
                        floorLabel = item.optString("floorLabel").ifBlank {
                            BeaconDisplayFormat.floorLabel(name)
                        },
                    )
                }
            }
            collapseHistoryByBeacon()
        }
    }

    private fun restoreOverrides() {
        val json = prefs.getString(KEY_FLOOR_OVERRIDES, null) ?: return
        runCatching {
            val obj = JSONObject(json)
            synchronized(floorOverrides) {
                floorOverrides.clear()
                obj.keys().forEach { key ->
                    val code = obj.optString(key)
                    if (BeaconDisplayFormat.isFloorCode(code)) {
                        floorOverrides[key] = code
                    }
                }
            }
        }
    }

    companion object {
        private const val MAJOR_100_REGION = "major-100-beacons"
        private const val PREFS_NAME = "beacon_tracker"
        private const val KEY_RECORDS = "closest_records"
        private const val KEY_CLOSEST_ID = "closest_id"
        private const val KEY_CLOSEST_NAME = "closest_name"
        private const val KEY_FLOOR_OVERRIDES = "floor_overrides"
        private const val KEY_SELECTED_FLOOR = "selected_floor"
        private const val KEY_HISTORY = "closest_history"
        private const val LEFT_ZONE_MISS_COUNT = 2
        private const val MAX_HISTORY = 50
        private const val SCAN_MS = 1100L
        private const val IDLE_BETWEEN_MS = 10_000L
        private const val ACTIVE_BETWEEN_MS = 0L

        @Volatile
        private var instance: BeaconTracker? = null

        fun get(app: Application): BeaconTracker {
            return instance ?: synchronized(this) {
                instance ?: BeaconTracker(app).also { instance = it }
            }
        }

        fun peek(): BeaconTracker? = instance
    }
}
