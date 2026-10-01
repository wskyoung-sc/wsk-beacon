package com.beacon.testapp

import java.util.Calendar
import java.util.Locale
import kotlin.math.round

object BeaconDisplayFormat {
    fun distance(meters: Double): String {
        val totalCm = round(meters * 100.0).toInt().coerceAtLeast(0)
        val meterPart = totalCm / 100
        val cmPart = totalCm % 100
        return when {
            meterPart == 0 -> String.format(Locale.KOREA, "%d cm", cmPart)
            cmPart == 0 -> String.format(Locale.KOREA, "%d m", meterPart)
            else -> String.format(Locale.KOREA, "%d m %d cm", meterPart, cmPart)
        }
    }

    fun koreanDateTime(epochMs: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = epochMs
        return String.format(
            Locale.KOREA,
            "%d월 %d일  %d시 %d분 %d초",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }

    fun koreanDateTimeCompact(epochMs: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = epochMs
        return String.format(
            Locale.KOREA,
            "%d월 %d일 %d:%02d:%02d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }

    fun koreanDateTimeWidget(epochMs: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = epochMs
        return String.format(
            Locale.KOREA,
            "%d월 %d일\n%d:%02d",
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    fun isFloorCode(code: String?): Boolean {
        return code == "B1" || code == "B2" || code == "1F"
    }

    fun floorCode(beacon: DetectedBeacon): String? {
        val override = beacon.floorOverride
        if (isFloorCode(override)) return override
        return floorCodeFromName(beacon.name)
    }

    fun floorCodeFromName(name: String): String? {
        val prefix = name.trim().take(2).uppercase(Locale.ROOT)
        return when (prefix) {
            "B1" -> "B1"
            "B2" -> "B2"
            "1F" -> "1F"
            else -> null
        }
    }

    fun floorLabelFromCode(code: String?): String? {
        return when (code) {
            "B1" -> "지하1층"
            "B2" -> "지하2층"
            "1F" -> "1층"
            else -> null
        }
    }

    fun floorLabel(name: String): String {
        return floorLabelFromCode(floorCodeFromName(name)) ?: name
    }

    fun floorLabel(beacon: DetectedBeacon): String {
        return floorLabelFromCode(floorCode(beacon)) ?: beacon.name
    }

    fun widgetLabel(beacon: DetectedBeacon): String = floorLabel(beacon)

    fun floorColorArgb(code: String?): Int {
        return when (code) {
            "B1" -> COLOR_B1
            "B2" -> COLOR_B2
            "1F" -> COLOR_1F
            else -> COLOR_UNKNOWN
        }
    }

    fun floorColorArgb(beacon: DetectedBeacon?): Int {
        return floorColorArgb(beacon?.let { floorCode(it) })
    }

    fun floorTextColorArgb(code: String?): Int {
        return when (code) {
            "B2" -> COLOR_B2_TEXT
            "1F" -> COLOR_1F_TEXT
            "B1" -> COLOR_B1_TEXT
            else -> COLOR_UNKNOWN
        }
    }

    val COLOR_B1 = 0xFF3DB8FF.toInt()
    val COLOR_B2 = 0xFFFF9A1F.toInt()
    val COLOR_1F = 0xFF30C46B.toInt()
    val COLOR_UNKNOWN = 0xFFFFFFFF.toInt()
    val COLOR_B1_TEXT = 0xFFFFFFFF.toInt()
    val COLOR_B2_TEXT = 0xFF1C1400.toInt()
    val COLOR_1F_TEXT = 0xFFFFFFFF.toInt()
}
