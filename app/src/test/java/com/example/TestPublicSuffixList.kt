package com.example

import com.example.service.PublicSuffixList
import java.io.File

/** The bundled Public Suffix List, read straight from the main source set (plain JVM tests have no assets). */
object TestPublicSuffixList {
    val list: PublicSuffixList by lazy {
        val file = listOf(
            "src/main/assets/${PublicSuffixList.ASSET_NAME}",
            "app/src/main/assets/${PublicSuffixList.ASSET_NAME}"
        ).map(::File).firstOrNull { it.isFile }
            ?: error("${PublicSuffixList.ASSET_NAME} not found in src/main/assets")
        file.inputStream().use { PublicSuffixList.parse(it) }
    }
}
