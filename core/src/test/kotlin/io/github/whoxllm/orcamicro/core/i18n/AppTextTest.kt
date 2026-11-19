package io.github.whoxllm.orcamicro.core.i18n

import io.github.whoxllm.orcamicro.core.settings.OrcaOptions
import io.github.whoxllm.orcamicro.core.settings.Russian
import kotlinx.serialization.json.*
import kotlin.test.*

class AppTextTest {
    @Test fun `language switch uses original option labels and keeps user data verbatim`() {
        try {
            AppText.english=true
            assertEquals("Nozzle: 0.4 mm",tr("Сопло: {0} мм","0.4"))
            assertEquals("Filament: Мой PLA {0}",tr("Пластик: {0}","Мой PLA {0}"))
            val option=OrcaOptions.all.first{it.key=="layer_height"};assertEquals(option.label,option.displayLabel);assertEquals(option.description,option.displayDescription)
            assertEquals("Generating infill",Russian.text("Generating infill"))
            AppText.english=false
            assertEquals("Сопло: 0.4 мм",tr("Сопло: {0} мм","0.4"));assertEquals(option.labelRu,option.displayLabel)
        }finally{AppText.english=false}
    }
    @Test fun `parameter language is independent from interface language including values and help`() {
        val option=OrcaOptions.all.first{it.key=="layer_height"}
        val boolean=OrcaOptions.all.first{it.valueType=="coBool"}
        try {
            AppText.english=true;AppText.parameterLanguage=ParameterLanguage.RUSSIAN
            assertEquals("Save",tr("Сохранить"));assertEquals(option.labelRu,option.displayLabel);assertEquals(option.descriptionRu,option.displayDescription)
            assertEquals(option.categoryRu,option.displayCategory);assertEquals("Включено",OrcaOptions.display(boolean,JsonPrimitive("1")))
            assertEquals("Высота слоя, мм",ptr("Высота слоя, мм"))
            val unknown=OrcaOptions.forType(option.type,buildJsonObject{put("fixture_unknown",1)}).single{it.key=="fixture_unknown"}
            assertEquals("Из профиля",unknown.displayCategory)
            AppText.english=false;AppText.parameterLanguage=ParameterLanguage.ENGLISH
            assertEquals("Сохранить",tr("Сохранить"));assertEquals(option.label,option.displayLabel);assertEquals(option.description,option.displayDescription)
            assertEquals(option.category,option.displayCategory);assertEquals("On",OrcaOptions.display(boolean,JsonPrimitive("1")))
            assertEquals("From profile",unknown.displayCategory)
            AppText.parameterLanguage=ParameterLanguage.INTERFACE
            assertEquals(option.labelRu,option.displayLabel)
            AppText.english=true;assertEquals(option.label,option.displayLabel)
        } finally { AppText.english=false;AppText.parameterLanguage=ParameterLanguage.INTERFACE }
    }
    @Test fun `every translation preserves dynamic arguments and English has no Cyrillic UI text`() {
        val root=javaClass.getResourceAsStream("/app-en.json")!!.bufferedReader().use{Json.parseToJsonElement(it.readText()).jsonObject}
        assertTrue(root.size>=680)
        val argument=Regex("\\{[0-9]+\\}")
        for((ru,value) in root){val en=value.jsonPrimitive.content;assertEquals(argument.findAll(ru).map{it.value}.sorted().toList(),argument.findAll(en).map{it.value}.sorted().toList(),ru);assertFalse(en.contains(Regex("[А-Яа-яЁё]")),en)}
    }
}
