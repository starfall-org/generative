package org.starfall.generative.features.appdata.model

import java.io.File

data class ZipAppDataBackup(
    val data: AppDataBackup,
    val iconFiles: Map<String, File> = emptyMap(),
    val toolFiles: Map<String, File> = emptyMap(),
    val remoteIdentityFiles: Map<String, Pair<File, File?>> = emptyMap(),
    val skillFiles: Map<String, File> = emptyMap(),
    val uploadFiles: Map<String, File> = emptyMap()
)
