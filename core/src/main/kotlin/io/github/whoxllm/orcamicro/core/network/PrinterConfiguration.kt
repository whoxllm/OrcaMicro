package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.json.*
import kotlin.math.*

/** Read-only conversion of known printer geometry; macros and unknown limits stay in the user's base preset. */
object PrinterConfiguration {
    private fun JsonObject.number(key:String)=this[key]?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()}
    private fun array(value:Double)=JsonArray(listOf(JsonPrimitive(value.toString())))
    fun klipper(config:JsonObject,toolhead:JsonObject?):DetectedPrinterConfig {
        fun section(name:String)=(config[name] as? JsonObject) ?: JsonObject(emptyMap())
        fun limit(axis:String,key:String,index:Int):Double?=section("stepper_${axis}").number(key) ?: (toolhead?.get(if(key=="position_min")"axis_minimum" else "axis_maximum") as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()}
        val minX=limit("x","position_min",0) ?: 0.0;val minY=limit("y","position_min",1) ?: 0.0
        val maxX=limit("x","position_max",0);val maxY=limit("y","position_max",1);val maxZ=limit("z","position_max",2)
        val notes=mutableListOf(tr("Границы осей Klipper могут отличаться от полезной площади стола. Проверьте геометрию перед сохранением."),tr("Стартовый и конечный G-code, ретракты и отсутствующие параметры остаются из выбранного профиля."))
        val values=buildJsonObject {
            if(maxX!=null&&maxY!=null&&maxX>minX&&maxY>minY)put("printable_area",JsonArray(listOf("${minX}x${minY}","${maxX}x${minY}","${maxX}x${maxY}","${minX}x${maxY}").map(::JsonPrimitive)))
            maxZ?.takeIf{it>0}?.let{put("printable_height",JsonPrimitive(it.toString()))}
            section("extruder").number("nozzle_diameter")?.takeIf{it in 0.05..5.0}?.let{put("nozzle_diameter",array(it))}
            section("printer").number("max_velocity")?.takeIf{it>0}?.let{put("machine_max_speed_x",array(it));put("machine_max_speed_y",array(it))}
            section("printer").number("max_z_velocity")?.takeIf{it>0}?.let{put("machine_max_speed_z",array(it))}
            section("printer").number("max_accel")?.takeIf{it>0}?.let{put("machine_max_acceleration_x",array(it));put("machine_max_acceleration_y",array(it));put("machine_max_acceleration_extruding",array(it));put("machine_max_acceleration_travel",array(it))}
            section("printer").number("max_z_accel")?.takeIf{it>0}?.let{put("machine_max_acceleration_z",array(it))}
            put("gcode_flavor",JsonPrimitive("klipper"))
        }
        require(values.size>1){tr("Klipper не предоставил параметры геометрии или сопла")}
        return DetectedPrinterConfig("Klipper / configfile",values,notes)
    }
    fun octoprint(profile:JsonObject):DetectedPrinterConfig {
        val volume=profile["volume"]?.jsonObject ?: error(tr("OctoPrint не предоставил геометрию"))
        val w=volume.number("width");val d=volume.number("depth");val h=volume.number("height")
        require(w!=null&&d!=null&&h!=null&&w>0&&d>0&&h>0){tr("Проверьте объём печати в профиле OctoPrint")}
        val custom=volume["custom_box"] as? JsonObject
        val centered=volume["origin"]?.jsonPrimitive?.content=="center"
        val minX=custom?.number("x_min") ?: if(centered)-w/2 else 0.0;val minY=custom?.number("y_min") ?: if(centered)-d/2 else 0.0
        val maxX=custom?.number("x_max") ?: minX+w;val maxY=custom?.number("y_max") ?: minY+d
        val points=if(volume["formFactor"]?.jsonPrimitive?.content=="circular") (0 until 48).map { i->val a=i*2*PI/48;"${(minX+maxX)/2+(maxX-minX)/2*cos(a)}x${(minY+maxY)/2+(maxY-minY)/2*sin(a)}" }
            else listOf("${minX}x${minY}","${maxX}x${minY}","${maxX}x${maxY}","${minX}x${maxY}")
        val values=buildJsonObject {
            put("printable_area",JsonArray(points.map(::JsonPrimitive)));put("printable_height",JsonPrimitive((custom?.number("z_max") ?: h).toString()))
            (profile["extruder"] as? JsonObject)?.number("nozzleDiameter")?.takeIf{it in 0.05..5.0}?.let{put("nozzle_diameter",array(it))}
        }
        return DetectedPrinterConfig("OctoPrint / printer profile",values,listOf(tr("Это профиль OctoPrint, а не дамп EEPROM прошивки. Проверьте размеры и сопло."),tr("Тип G-code, стартовые команды и отсутствующие параметры остаются из выбранного профиля.")))
    }
}
