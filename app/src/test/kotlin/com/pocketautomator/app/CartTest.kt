package com.pocketautomator.app

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads real carts when the environment points at them (POCKET_SWITCH_DIR
 * and POCKET_PROD_KEYS); skipped otherwise, since the files aren't in the repo.
 */
class CartTest {

    @Test
    fun `title IDs from real carts`() {
        val dir = System.getenv("POCKET_SWITCH_DIR")?.let(::File)
        val keys = System.getenv("POCKET_PROD_KEYS")?.let(::File)
        assumeTrue(dir != null && keys != null && dir.isDirectory && keys.isFile)
        val key = GameIds.headerKey(keys!!.readText())!!
        dir!!.listFiles()!!.filter { it.extension.lowercase() in setOf("xci", "nsp") }.sortedBy { it.name }.forEach { f ->
            println("${GameIds.switchFromCart(f, key) ?: "?"}\t${GameIds.switchFromNsp(f) ?: "-"}\t${f.name}")
        }
    }
}
