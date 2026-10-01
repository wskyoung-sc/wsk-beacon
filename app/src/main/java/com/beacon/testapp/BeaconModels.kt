package com.beacon.testapp

const val TARGET_MAJOR = 100
const val MEASURE_ZONE_METERS = 5.0

data class ApproachSession(
    val inside: Boolean,
    val record: ClosestApproach?,
    val missedScans: Int = 0,
)

data class ClosestApproach(
    val name: String,
    val distanceMeters: Double,
    val epochMs: Long,
    val visitId: String = "",
    val beaconId: String = "",
    val floorLabel: String = name,
)

data class ScanUiState(
    val isScanning: Boolean = false,
    val isMeasuring: Boolean = false,
    val beacons: List<DetectedBeacon> = emptyList(),
    val closestBeacon: DetectedBeacon? = null,
    val closestApproach: ClosestApproach? = null,
    val approachHistory: List<ClosestApproach> = emptyList(),
    val pingSeq: Long = 0L,
    val lastUpdateEpochMs: Long? = null,
    val isInMeasureRange: Boolean = false,
    val selectedFloor: String? = null,
    val errorMessage: String? = null,
)

fun DetectedBeacon.isInMeasureRange(): Boolean {
    return distanceMeters.isFinite() &&
        distanceMeters >= 0.0 &&
        distanceMeters <= MEASURE_ZONE_METERS
}

fun List<DetectedBeacon>.closestOrNull(): DetectedBeacon? {
    return minWithOrNull(
        compareBy<DetectedBeacon> { beacon ->
            if (beacon.distanceMeters.isFinite() && beacon.distanceMeters >= 0.0) {
                beacon.distanceMeters
            } else {
                Double.POSITIVE_INFINITY
            }
        }.thenByDescending { it.rssi },
    )
}
