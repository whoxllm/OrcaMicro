package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

@Serializable
data class SavedPrinter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val connection: PrinterConnection,
    val machineProfile: String? = null,
)

data class HeaterState(val name: String, val temperature: Float, val target: Float?, val min: Float = 0f, val max: Float? = null) {
    val label: String get() = when(name) {
        "extruder" -> tr("Сопло"); "heater_bed" -> tr("Стол")
        else -> name.removePrefix("heater_generic ").removePrefix("temperature_sensor ").removePrefix("temperature_fan ")
    }
}
data class TemperatureSeries(val name: String, val temperatures: List<Float?>, val targets: List<Float?>, val timestampsMillis:List<Long> = emptyList(), val receivedAtMillis:Long = System.currentTimeMillis())
data class KlipperSnapshot(
    val ready: Boolean = false,
    val message: String = "",
    val heaters: List<HeaterState> = emptyList(),
    val homedAxes: String = "",
    val position: List<Float> = listOf(0f, 0f, 0f),
    val axisMinimum: List<Float> = listOf(0f, 0f, 0f),
    val axisMaximum: List<Float> = listOf(0f, 0f, 0f),
    val printState: String = "",
    val filename: String = "",
    val progress: Float = 0f,
    val fan: Float? = null,
    val printSeconds:Double=0.0,val totalSeconds:Double=0.0,val startedAtMillis:Long?=null,val remainingSeconds:Double?=null,val startEstimated:Boolean=false,
    val positionKnown:Boolean=true,

) {
    val printing: Boolean get() = printState in setOf("printing", "paused")
}

/** Builds bounded commands from current Klipper telemetry and preserves coordinate modes. */
object KlipperControl {
    fun heater(snapshot: KlipperSnapshot, name: String, target: Float): String {
        require(snapshot.ready) { tr("Klipper не готов") }
        val heater = snapshot.heaters.firstOrNull { it.name == name && it.target != null } ?: error(tr("Нагреватель недоступен"))
        require(target.isFinite() && (target == 0f || (heater.max != null && target in maxOf(0f, heater.min)..heater.max))) { tr("Температура вне диапазона конфигурации Klipper") }
        require(name.matches(Regex("[A-Za-z0-9_ -]+"))) { tr("Недопустимое имя нагревателя") }
        return if(name.startsWith("temperature_fan ")) "SET_TEMPERATURE_FAN_TARGET TEMPERATURE_FAN=\"${name.removePrefix("temperature_fan ")}\" TARGET=${number(target)}"
        else "SET_HEATER_TEMPERATURE HEATER=\"${name.removePrefix("heater_generic ")}\" TARGET=${number(target)}"
    }

    fun move(snapshot: KlipperSnapshot, axis: String, amount: Float): String {
        require(snapshot.ready && !snapshot.printing) { tr("Перемещение недоступно во время печати") }
        require(axis in listOf("X", "Y", "Z") && axis.lowercase() in snapshot.homedAxes) { tr("Сначала выполните Home для этой оси") }
        require(amount.isFinite() && kotlin.math.abs(amount) in 0.01f..50f) { tr("Недопустимый шаг перемещения") }
        val i = listOf("X", "Y", "Z").indexOf(axis)
        val target = snapshot.position[i] + amount
        require(target in snapshot.axisMinimum[i]..snapshot.axisMaximum[i]) { tr("Перемещение выходит за границы оси") }
        val speed = if(axis == "Z") 5 else 30
        return "SAVE_GCODE_STATE NAME=ORCA_ANDROID_JOG\nG91\nG1 ${axis}${number(amount)} F${speed*60}\nRESTORE_GCODE_STATE NAME=ORCA_ANDROID_JOG"
    }

    fun home(snapshot: KlipperSnapshot, axes: String): String {
        require(snapshot.ready && !snapshot.printing) { tr("Home недоступен во время печати") }
        require(axes in listOf("", "X", "Y", "Z", "XY"))
        return "G28" + if(axes.isBlank()) "" else " " + axes.map { "${it}" }.joinToString(" ")
    }

    fun fan(snapshot: KlipperSnapshot, percent: Int): String {
        require(snapshot.ready && snapshot.fan != null && percent in 0..100) { tr("Вентилятор недоступен") }
        return "M106 S${(percent*255/100)}"
    }
    private fun number(value: Float) = String.format(Locale.ROOT, "%.2f", value)
}
