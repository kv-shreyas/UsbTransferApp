package com.example.securequicktransferapp.domain.constants

object Constants {
    object SmartnavRoot {
        // Base / Root folder names
        const val ROOT_FOLDER_NAME = "SmartNavV3"
        const val DEFAULT_SDCARD_ROOT_PATH = "/sdcard/$ROOT_FOLDER_NAME"
        const val DEFAULT_APP_EXTERNAL_ROOT_PATH = "/sdcard/Android/data/com.example.smartnav_v3/files/$ROOT_FOLDER_NAME"

        // Directory Names
        const val DIR_PASSWORD = "password"
        const val DIR_TRACKS = "tracks"
        const val DIR_TRACKS_META = "meta"
        const val DIR_TRACE = "trace"
        const val DIR_IMEI = "IMEI"
        const val DIR_FIRMWARE_UPGRADE = "firmwareUpgrade"
        const val DIR_MAPS = "maps"
        const val DIR_MAPS_RASTER = "raster"
        const val DIR_MAPS_VECTOR = "vector"
        const val DIR_MAPS_ICONS = "icons"
        const val DIR_DATABASE = "database"
        const val DIR_LOG_MANAGER = "logManager"
        const val DIR_GNSS_DATA_LOGS = "gnssDataLogs"
        const val DIR_DEV_LOGS = "devLogs"
        const val DIR_CRASH_LOGS = "crashlogs"

        // External Paths
        const val PATH_APP_UPDATE = "/sdcard/updateApp"

        // File Names
        const val FILE_PASSWORD = "password.txt"
        const val FILE_MAINTENANCE_PASSWORD = "maintenancepassword.txt"
        const val FILE_KMM_PASSWORD = "kmmpassword.txt"
        const val FILE_KMM_NAV_PASSWORD = "kmm_nav_password.txt"
        const val FILE_KEEP_PLACEHOLDER = ".keep"

        // Default Password & Counter Values
        const val DEFAULT_PASSWORD_VALUE = "123456"
        const val DEFAULT_MAINTENANCE_PASSWORD_VALUE = "A@#\$rdDEV"
        const val DEFAULT_KMM_PASSWORD_VALUE = "123456"
        const val DEFAULT_KMM_NAV_PASSWORD_VALUE = "123456"
        const val DEFAULT_LOG_COUNTER_VALUE = "0"

        val DEFAULT_FOLDERS_AND_FILES: List<Pair<String, Pair<String, String>>> = listOf(
            Pair(DIR_PASSWORD, Pair(FILE_PASSWORD, DEFAULT_PASSWORD_VALUE)),
            Pair(DIR_PASSWORD, Pair(FILE_MAINTENANCE_PASSWORD, DEFAULT_MAINTENANCE_PASSWORD_VALUE)),
            Pair(DIR_PASSWORD, Pair(FILE_KMM_PASSWORD, DEFAULT_KMM_PASSWORD_VALUE)),
            Pair(DIR_PASSWORD, Pair(FILE_KMM_NAV_PASSWORD, DEFAULT_KMM_NAV_PASSWORD_VALUE)),
//            Pair("$DIR_TRACKS/$DIR_TRACKS_META", Pair(FILE_KEEP_PLACEHOLDER, "")),
//            Pair(DIR_TRACE, Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair(DIR_IMEI, Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair("updateApp", Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair(DIR_FIRMWARE_UPGRADE, Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair("$DIR_MAPS/$DIR_MAPS_RASTER", Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair("$DIR_MAPS/$DIR_MAPS_VECTOR", Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair("$DIR_MAPS/$DIR_MAPS_ICONS", Pair(FILE_KEEP_PLACEHOLDER, "")),
            Pair(DIR_DATABASE, Pair(FILE_KEEP_PLACEHOLDER, "")),
//            Pair(DIR_GNSS_DATA_LOGS, Pair(FILE_KEEP_PLACEHOLDER, ""))
        )
    }
}
