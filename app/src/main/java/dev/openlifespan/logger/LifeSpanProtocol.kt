package dev.openlifespan.logger

import java.util.Locale
import java.util.UUID

object LifeSpanProtocol {
    val serviceUuid: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val notifyUuid: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val writeUuid: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val clientConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROPERTY_UNITS = 0x81
    const val PROPERTY_SPEED = 0x82
    const val PROPERTY_INCLINE = 0x83
    const val PROPERTY_DISTANCE = 0x85
    const val PROPERTY_CALORIES = 0x87
    const val PROPERTY_STEPS = 0x88
    const val PROPERTY_ELAPSED_TIME = 0x89
    const val PROPERTY_DEVICE_STATE = 0x91
    const val PROPERTY_WORKOUT_STATUS = 0x94
    const val PROPERTY_MAX_SPEED = 0x71

    fun requestProperty(property: Int): ByteArray {
        return byteArrayOf(0xA1.toByte(), property.toByte(), 0x00, 0x00, 0x00)
    }

    fun clearStoredData(): ByteArray {
        return byteArrayOf(0xAB.toByte(), 0x01, 0x00, 0x00, 0x00)
    }

    fun engageExternalControl(): ByteArray {
        return byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x00)
    }

    fun resetCounters(): ByteArray {
        return byteArrayOf(0xE2.toByte(), 0x00, 0x00, 0x00, 0x00)
    }

    fun escapeToIdle(): ByteArray {
        return byteArrayOf(0xAB.toByte(), 0x02, 0x00, 0x00, 0x00)
    }

    fun setSpeed(speedHundredths: Int): ByteArray {
        val whole = (speedHundredths / 100).coerceIn(0, 12)
        val fractional = (speedHundredths % 100).coerceIn(0, 99)
        return byteArrayOf(0xD0.toByte(), whole.toByte(), fractional.toByte(), 0x00, 0x00)
    }

    fun parsePropertyResponse(property: Int, value: ByteArray): PropertyValue? {
        if (value.size < 6 || value[0].toUnsignedInt() != 0xA1 || value[1].toUnsignedInt() != 0xAA) {
            return null
        }

        val first = value[2].toUnsignedInt()
        val second = value[3].toUnsignedInt()
        val third = value[4].toUnsignedInt()
        val twoByteValue = (first shl 8) or second
        val decimalValue = (first * 100 + second) / 100.0
        val hmsSeconds = first * 3600 + second * 60 + third

        return when (property) {
            PROPERTY_UNITS -> PropertyValue.Units(first)
            PROPERTY_SPEED -> PropertyValue.Speed(decimalValue)
            PROPERTY_INCLINE -> PropertyValue.Incline(decodeIncline(first))
            PROPERTY_DISTANCE -> PropertyValue.Distance(decimalValue)
            PROPERTY_CALORIES -> PropertyValue.Calories(twoByteValue)
            PROPERTY_STEPS -> PropertyValue.Steps(twoByteValue)
            PROPERTY_ELAPSED_TIME -> PropertyValue.ElapsedTime(hmsSeconds)
            PROPERTY_DEVICE_STATE -> PropertyValue.DeviceState(first)
            PROPERTY_WORKOUT_STATUS -> PropertyValue.WorkoutStatus(first)
            PROPERTY_MAX_SPEED -> PropertyValue.MaxSpeed(decimalValue)
            else -> PropertyValue.Raw(property, value.copyOf())
        }
    }

    fun isOk(value: ByteArray?): Boolean {
        return value != null && value.size >= 2 && value[1].toUnsignedInt() == 0xAA
    }

    fun formatSpeed(speed: Double): String = "%.2f".format(Locale.US, speed)

    fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, remainingSeconds)
    }

    private fun decodeIncline(value: Int): Int {
        val magnitude = value and 0x7F
        return if (magnitude <= 50) value else 50 - value
    }
}

sealed class PropertyValue {
    data class Units(val value: Int) : PropertyValue()
    data class Speed(val value: Double) : PropertyValue()
    data class Incline(val value: Int) : PropertyValue()
    data class Distance(val value: Double) : PropertyValue()
    data class Calories(val value: Int) : PropertyValue()
    data class Steps(val value: Int) : PropertyValue()
    data class ElapsedTime(val seconds: Int) : PropertyValue()
    data class DeviceState(val value: Int) : PropertyValue()
    data class WorkoutStatus(val value: Int) : PropertyValue()
    data class MaxSpeed(val value: Double) : PropertyValue()
    data class Raw(val property: Int, val bytes: ByteArray) : PropertyValue()
}

fun ByteArray.toHex(length: Int = size): String {
    return take(length).joinToString(" ") { byte -> "%02X".format(byte) }
}

fun Byte.toUnsignedInt(): Int = toInt() and 0xFF
