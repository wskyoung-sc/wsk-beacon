package com.beacon.testapp

data class DetectedBeacon(
    val id: String,
    val type: String,
    val name: String,
    val uuid: String,
    val major: String,
    val minor: String,
    val rssi: Int,
    val txPower: Int,
    val distanceMeters: Double,
    val bluetoothAddress: String,
    val floorOverride: String? = null,
) {
    companion object {
        fun from(beacon: org.altbeacon.beacon.Beacon): DetectedBeacon {
            val identifiers = beacon.identifiers
            val uuid = identifiers.getOrNull(0)?.toString() ?: "-"
            val major = identifiers.getOrNull(1)?.toString() ?: "-"
            val minor = identifiers.getOrNull(2)?.toString() ?: "-"
            val bluetoothName = beacon.bluetoothName?.trim().orEmpty()
            return DetectedBeacon(
                id = listOf(beacon.bluetoothAddress, uuid, major, minor).joinToString("|"),
                type = beaconType(beacon),
                name = displayName(bluetoothName, uuid, major, minor),
                uuid = uuid,
                major = major,
                minor = minor,
                rssi = beacon.rssi,
                txPower = beacon.txPower,
                distanceMeters = beacon.distance,
                bluetoothAddress = beacon.bluetoothAddress ?: "-",
            )
        }

        fun placeholder(id: String, name: String): DetectedBeacon {
            return DetectedBeacon(
                id = id,
                type = "",
                name = name,
                uuid = "-",
                major = "-",
                minor = "-",
                rssi = 0,
                txPower = 0,
                distanceMeters = Double.POSITIVE_INFINITY,
                bluetoothAddress = "-",
            )
        }

        private fun displayName(
            bluetoothName: String,
            uuid: String,
            major: String,
            minor: String,
        ): String {
            if (bluetoothName.isNotBlank()) return bluetoothName
            if (major != "-" && minor != "-") return "$major-$minor"
            return uuid
        }

        private fun beaconType(beacon: org.altbeacon.beacon.Beacon): String {
            val parserId = beacon.parserIdentifier ?: ""
            return when {
                parserId.contains("ibeacon", ignoreCase = true) -> "iBeacon"
                parserId.contains("altbeacon", ignoreCase = true) -> "AltBeacon"
                parserId.contains("eddystone", ignoreCase = true) -> "Eddystone"
                beacon.serviceUuid == 0xFEAA -> "Eddystone"
                else -> "BLE Beacon"
            }
        }
    }
}
