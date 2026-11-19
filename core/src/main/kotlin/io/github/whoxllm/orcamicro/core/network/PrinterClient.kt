package io.github.whoxllm.orcamicro.core.network

import java.io.File
import kotlinx.serialization.json.JsonObject

enum class PrinterBackend { MOONRAKER, OCTOPRINT }
interface PrinterClient {
    fun snapshot():KlipperSnapshot
    fun temperatureHistory():List<TemperatureSeries>
    fun info():String
    fun setHeater(name:String,target:Float)
    fun jog(axis:String,amount:Float)
    fun home(axes:String="")
    fun setFan(percent:Int)
    fun heatersOff()
    fun motorsOff()
    fun emergencyStop()
    fun pause()
    fun resume()
    fun cancelPrint()
    fun startPrint(filename:String)
    fun upload(file:File,name:String,onProgress:(Int)->Unit={}):String
    fun statistics():PrinterStatistics
    fun readConfiguration():DetectedPrinterConfig
}
fun printerClient(connection:PrinterConnection):PrinterClient=when(connection.backend) {
    PrinterBackend.MOONRAKER->MoonrakerClient(connection)
    PrinterBackend.OCTOPRINT->OctoPrintClient(connection)
}
data class DetectedPrinterConfig(val source:String,val values:JsonObject,val notes:List<String> = emptyList())
data class PrintHistoryJob(val id:String,val filename:String,val status:String,val startedAt:Long?,val endedAt:Long?,val printSeconds:Double,val totalSeconds:Double,val filamentMm:Double)
data class PrinterStatistics(
    val completed:Long=0,val failed:Long=0,val cancelled:Long=0,val active:Long=0,val other:Long=0,
    val printSeconds:Double?=0.0,val totalSeconds:Double?=0.0,val filamentMm:Double?=0.0,val longestSeconds:Double?=0.0,
    val recent:List<PrintHistoryJob> = emptyList(),val limited:Boolean=false,val note:String?=null,
) {
    val finished:Long get()=completed+failed+cancelled+other
    val successRate:Double? get()=if(finished>0)completed*100.0/finished else null
    companion object {
        fun from(jobs:List<PrintHistoryJob>,limited:Boolean=false):PrinterStatistics=PrinterStatistics(
            completed=jobs.count{it.status=="completed"}.toLong(),failed=jobs.count{it.status in setOf("error","klippy_shutdown","klippy_disconnect","interrupted")}.toLong(),
            cancelled=jobs.count{it.status=="cancelled"}.toLong(),active=jobs.count{it.status=="in_progress"}.toLong(),
            other=jobs.count{it.status !in setOf("completed","error","klippy_shutdown","klippy_disconnect","interrupted","cancelled","in_progress")}.toLong(),
            printSeconds=jobs.sumOf{it.printSeconds},totalSeconds=jobs.sumOf{it.totalSeconds},filamentMm=jobs.sumOf{it.filamentMm},longestSeconds=jobs.maxOfOrNull{it.totalSeconds} ?: 0.0,
            recent=jobs.take(15),limited=limited)
        fun combine(items:List<PrinterStatistics>):PrinterStatistics {
            fun total(value:(PrinterStatistics)->Double?):Double?=if(items.isEmpty()||items.any{value(it)==null})null else items.sumOf{value(it)!!}
            return PrinterStatistics(items.sumOf{it.completed},items.sumOf{it.failed},items.sumOf{it.cancelled},items.sumOf{it.active},items.sumOf{it.other},
                total{it.printSeconds},total{it.totalSeconds},total{it.filamentMm},if(items.any{it.longestSeconds==null})null else items.maxOfOrNull{it.longestSeconds ?: 0.0},
                items.flatMap{it.recent}.sortedByDescending{it.startedAt}.take(15),items.any{it.limited})
        }
    }
}
object PrintTiming {
    /** Prefer firmware ETA; progress extrapolation is deliberately withheld at the start. */
    fun remaining(progress:Float,printSeconds:Double,estimatedSeconds:Double?):Double? {
        if(progress.isFinite()&&progress in 0.02f..0.999999f&&printSeconds>=30)return (printSeconds/progress-printSeconds).coerceAtLeast(0.0).takeIf{it.isFinite()&&it<=365*86400.0}
        return estimatedSeconds?.takeIf{it.isFinite()&&it>0}?.let{(it-printSeconds).coerceAtLeast(0.0)}
    }
}
