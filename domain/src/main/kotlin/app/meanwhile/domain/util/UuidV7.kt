package app.meanwhile.domain.util

import java.security.SecureRandom
import java.util.UUID

/**
 * RFC 9562 UUIDv7: 48-bit Unix-epoch milliseconds, version 7, 74 random bits.
 * Time-ordered, so ids sort by creation time on device and in Postgres.
 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(epochMillis: Long = System.currentTimeMillis()): UUID {
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong()
        val msb = (epochMillis and 0xFFFF_FFFF_FFFFL shl 16) or (0x7L shl 12) or randA
        val lsb = (randB and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE // variant 0b10
        return UUID(msb, lsb)
    }

    fun string(epochMillis: Long = System.currentTimeMillis()): String = generate(epochMillis).toString()

    fun timestampOf(uuid: UUID): Long = uuid.mostSignificantBits ushr 16
}
