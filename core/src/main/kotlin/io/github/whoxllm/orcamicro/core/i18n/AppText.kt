package io.github.whoxllm.orcamicro.core.i18n

import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

@Serializable
enum class ParameterLanguage { INTERFACE, RUSSIAN, ENGLISH }

/** Built-in UI and error messages only. User names, model metadata and raw diagnostics are never translated. */
object AppText {
    @Volatile var english:Boolean=false
    @Volatile var parameterLanguage:ParameterLanguage=ParameterLanguage.INTERFACE
    val parametersEnglish:Boolean get()=when(parameterLanguage) {
        ParameterLanguage.INTERFACE -> english
        ParameterLanguage.RUSSIAN -> false
        ParameterLanguage.ENGLISH -> true
    }
    private val translations:Map<String,String> by lazy {
        javaClass.getResourceAsStream("/app-en.json")!!.bufferedReader().use { input->Json.parseToJsonElement(input.readText()).jsonObject.mapValues{it.value.jsonPrimitive.content} }
    }
    private val placeholder=Regex("\\{([0-9]+)\\}")
    fun text(source:String,vararg values:Any?):String=translate(source,english,*values)
    fun parameterText(source:String,vararg values:Any?):String=translate(source,parametersEnglish,*values)
    private fun translate(source:String,useEnglish:Boolean,vararg values:Any?):String {
        val template=if(useEnglish)translations[source] ?: source else source
        return placeholder.replace(template){match->val index=match.groupValues[1].toInt();if(index in values.indices)values[index]?.toString().orEmpty()else match.value}
    }
}
fun tr(source:String,vararg values:Any?):String=AppText.text(source,*values)

fun ptr(source:String,vararg values:Any?):String=AppText.parameterText(source,*values)
