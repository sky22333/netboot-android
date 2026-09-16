package com.sky22333.netboot.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Reads image bytes only; never opens a device or mounts a filesystem. */
object UsbMediaLayout {
    const val OpticalLimit: Long = (256L * 60 * 75 - 1) * 2048

    fun isDisk(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 1024 || input.length() % 512 != 0L) return false
        val mbr = ByteArray(512).also(input::readFully)
        if (mbr[510] != 0x55.toByte() || mbr[511] != 0xaa.toByte()) return false
        val b = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        val sectors = input.length() / 512
        var found = false
        var protective = false
        repeat(4) { i ->
            val offset = 446 + i * 16
            val type = mbr[offset + 4].toInt() and 255
            val start = b.getInt(offset + 8).toLong() and 0xffffffffL
            val size = b.getInt(offset + 12).toLong() and 0xffffffffL
            if (type != 0 && size != 0L) {
                if (start >= sectors || size > sectors - start) return false
                found = true
                protective = protective || type == 0xee
            }
        }
        if (!found) return false
        if (!protective) return true
        val header = ByteArray(512).also(input::readFully)
        if (String(header, 0, 8, Charsets.US_ASCII) != "EFI PART") return false
        val g = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val size = g.getInt(12)
        if (size !in 92..512 || g.getLong(24) != 1L) return false
        val crc = g.getInt(16).toLong() and 0xffffffffL
        g.putInt(16, 0)
        if (CRC32().apply { update(header, 0, size) }.value != crc) return false
        val table = g.getLong(72)
        val count = g.getInt(80)
        val entrySize = g.getInt(84)
        if (table < 2 || count !in 1..4096 || entrySize !in 128..1024 || entrySize % 128 != 0) return false
        val bytes = count.toLong() * entrySize
        if (table >= sectors || bytes > (sectors - table) * 512) return false
        input.seek(table * 512)
        val entries = ByteArray(bytes.toInt()).also(input::readFully)
        if (CRC32().apply { update(entries) }.value != (g.getInt(88).toLong() and 0xffffffffL)) return false
        val partitions = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
        var usable = false
        repeat(count) { i ->
            val offset = i * entrySize
            if ((0 until 16).any { entries[offset + it] != 0.toByte() }) {
                val first = partitions.getLong(offset + 32)
                val last = partitions.getLong(offset + 40)
                if (first < 0 || last < first || last >= sectors) return false
                usable = true
            }
        }
        usable
    }

    fun requireOptical(file: File) {
        if (file.length() > OpticalLimit) throw IOException("media_optical_limit")
        requireIso(file)
    }

    /** ISO9660 and UDF volume recognition; file size and a self-computed hash are not validation. */
    fun requireIso(file: File) {
        if (file.length() < 300L * 2048 || file.length() % 2048 != 0L) throw IOException("media_invalid_iso")
        RandomAccessFile(file, "r").use { input ->
            val descriptor = ByteArray(7)
            for (sector in 16L..31L) {
                input.seek(sector * 2048)
                input.readFully(descriptor)
                val id = String(descriptor, 1, 5, Charsets.US_ASCII)
                if ((id == "CD001" && descriptor[6] == 1.toByte()) ||
                    (id in setOf("NSR02", "NSR03") && descriptor[0] == 0.toByte())) return
            }
            throw IOException("media_invalid_iso")
        }
    }
}
