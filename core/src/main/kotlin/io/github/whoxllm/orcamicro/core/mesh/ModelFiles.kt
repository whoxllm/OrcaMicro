package io.github.whoxllm.orcamicro.core.mesh

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Imports geometry only. A web model never supplies printer commands or print profiles. */
object ModelFiles {
    const val MAX_FILE_BYTES = 96 * 1024 * 1024
    private const val MAX_XML_BYTES = 48 * 1024 * 1024
    private const val MAX_TRIANGLES = 1_000_000

    fun entries(file: File): List<String> {
        require(file.length() in 1..MAX_FILE_BYTES.toLong()) { tr("Размер модели должен быть не больше 96 МБ") }
        if (!isZip(file)) return listOf(file.name)
        return ZipFile(file).use { zip ->
            if (rootModelPath(zip) != null) listOf(file.name)
            else zip.entries().asSequence().take(10001).toList().also { require(it.size <= 10000) { tr("Слишком много файлов в архиве") } }
                .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") && it.name.substringAfterLast('.').lowercase() in setOf("stl", "3mf") }
                .map { it.name }.also { require(it.isNotEmpty()) { tr("В архиве нет STL или 3MF") } }
        }
    }

    fun read(file: File, entry: String? = null): Mesh {
        require(file.length() in 1..MAX_FILE_BYTES.toLong()) { tr("Модель слишком большая (максимум 96 МБ)") }
        if (!isZip(file)) return StlReader.read(file.readBytes())
        return ZipFile(file).use { zip ->
            if(rootModelPath(zip) != null) read3mf(zip)
            else {
                val selected = entry ?: entries(file).singleOrNull() ?: error(tr("Выберите файл из архива"))
                val item = zip.getEntry(selected) ?: error(tr("Файл отсутствует в архиве"))
                require(!item.isDirectory)
                if(selected.endsWith(".3mf", true)) {
                    val tmp = File.createTempFile("orca-geometry-", ".3mf", file.parentFile)
                    try {
                        zip.getInputStream(item).use { input -> tmp.outputStream().use { out ->
                            val bytes = input.limited(MAX_FILE_BYTES); out.write(bytes)
                        } }
                        ZipFile(tmp).use(::read3mf)
                    } finally { tmp.delete() }
                } else zip.getInputStream(item).use { StlReader.read(it.limited(MAX_FILE_BYTES)) }
            }
        }
    }

    /** Orca/Bambu project settings are optional and must be confirmed independently of geometry. */
    fun projectSettings(file:File,entry:String?=null):JsonObject? {
        if(!isZip(file))return null
        return ZipFile(file).use { zip->
            if(rootModelPath(zip)==null) {
                if(entry?.endsWith(".3mf",true)!=true)return@use null
                val item=zip.getEntry(entry) ?: return@use null
                val tmp=File.createTempFile("orca-project-",".3mf",file.parentFile)
                try{zip.getInputStream(item).use{input->tmp.writeBytes(input.limited(MAX_FILE_BYTES))};return@use projectSettings(tmp)}finally{tmp.delete()}
            }
            val settings=zip.getEntry("Metadata/project_settings.config") ?: return@use null
            val data=zip.getInputStream(settings).use{it.limited(4*1024*1024)}
            Json.parseToJsonElement(data.decodeToString()) as? JsonObject
        }
    }
    private fun rootModelPath(zip:ZipFile):String? {
        zip.getEntry("3D/3dmodel.model")?.let{return it.name}
        val rel=zip.getEntry("_rels/.rels") ?: return null
        var target:String?=null
        val reader=SAXParserFactory.newInstance().apply{isNamespaceAware=true}.newSAXParser().xmlReader
        for(feature in listOf("http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities"))runCatching{reader.setFeature(feature,false)}
        reader.entityResolver=org.xml.sax.EntityResolver{_,_->error(tr("Внешние сущности XML запрещены"))}
        val handler=object:org.xml.sax.ext.DefaultHandler2(){
            override fun startDTD(name:String?,publicId:String?,systemId:String?){error(tr("DOCTYPE в 3MF запрещён"))}
            override fun startElement(uri:String?,local:String,qName:String?,a:Attributes){if(local=="Relationship"&&a.getValue("Type")?.endsWith("/3dmodel")==true){
                require(a.getValue("TargetMode")!="External"){tr("Недопустимый путь 3MF")}
                val path=a.getValue("Target") ?: return
                require(!path.contains(':')&&!path.contains('\\')){tr("Недопустимый путь 3MF")}
                target=java.nio.file.Paths.get(path.removePrefix("/")).normalize().toString().also{require(!it.startsWith("..")){tr("Недопустимый путь 3MF")}}
            }}
        }
        reader.contentHandler=handler;reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler)
        reader.parse(InputSource(zip.getInputStream(rel).use{it.limited(128*1024)}.inputStream()))
        return target?.takeIf{zip.getEntry(it)!=null}
    }

    private fun isZip(file: File) = file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
    private fun InputStream.limited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while(true) { val count=read(buffer); if(count<0) break; require(out.size()+count<=limit) { tr("Распакованная модель слишком большая") }; out.write(buffer,0,count) }
        return out.toByteArray()
    }

    private data class Reference(val id: String, val path: String?, val transform: FloatArray)
    private class ObjectMesh { val vertices = Floats(); val triangles = mutableListOf<IntArray>(); val components = mutableListOf<Reference>() }
    private data class Document(val unit: Float, val objects: Map<String,ObjectMesh>, val build: List<Reference>)
    private fun identity() = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0f,0f)
    private fun transform(text: String?): FloatArray = if(text.isNullOrBlank()) identity() else text.trim().split(Regex("\\s+")).map { it.toFloat().also { n -> require(n.isFinite()) } }.toFloatArray().also { require(it.size==12) { tr("Неверная матрица 3MF") } }
    private fun parse(bytes: ByteArray): Document {
        val objects = linkedMapOf<String,ObjectMesh>(); val build = mutableListOf<Reference>()
        var current: ObjectMesh? = null; var unit=1f; var vertices=false; var triangles=false; var components=false; var inBuild=false
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware=true }
        val reader = factory.newSAXParser().xmlReader
        // Android's SAX implementation does not expose every Xerces feature.
        // LexicalHandler rejects DOCTYPE, and the resolver rejects external entities.
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) runCatching { reader.setFeature(feature,false) }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> error(tr("Внешние сущности XML запрещены")) }
        val handler=object: org.xml.sax.ext.DefaultHandler2() {
            override fun startDTD(name:String?,publicId:String?,systemId:String?) { error(tr("DOCTYPE в 3MF запрещён")) }
            override fun startElement(uri:String?, local:String, qName:String?, a:Attributes) {
                fun value(name:String) = a.getValue(name)
                fun reference() = Reference(value("objectid") ?: error(tr("Нет objectid")), (0 until a.length).firstOrNull { a.getLocalName(it)=="path" }?.let(a::getValue), transform(value("transform")))
                when(local) {
                    "model" -> { unit=when(value("unit") ?: "millimeter") { "micron" -> .001f; "millimeter" -> 1f; "centimeter" -> 10f; "meter" -> 1000f; "inch" -> 25.4f; "foot" -> 304.8f; else -> error(tr("Неизвестные единицы 3MF")) } }
                    "object" -> { val id=value("id") ?: error(tr("Нет id объекта")); require(id !in objects && objects.size<10000); current=ObjectMesh().also { objects[id]=it } }
                    "vertices" -> vertices=true
                    "triangles" -> triangles=true
                    "components" -> components=true
                    "build" -> inBuild=true
                    "vertex" -> if(vertices) { val mesh=requireNotNull(current); for(axis in listOf("x","y","z")) mesh.vertices.add(value(axis)?.toFloat() ?: error(tr("Нет координаты"))); require(mesh.vertices.size<=MAX_TRIANGLES*9) }
                    "triangle" -> if(triangles) { val mesh=requireNotNull(current); require(mesh.triangles.size<MAX_TRIANGLES); mesh.triangles.add(IntArray(3) { value("v${it+1}")?.toInt() ?: error(tr("Нет индекса вершины")) }) }
                    "component" -> if(components) { val mesh=requireNotNull(current);require(mesh.components.size<10000);mesh.components.add(reference()) }
                    "item" -> if(inBuild && value("printable")!="0" && value("printable")!="false") { require(build.size<10000);build.add(reference()) }
                }
            }
            override fun endElement(uri:String?,local:String,qName:String?) { when(local) { "object" -> current=null; "vertices" -> vertices=false; "triangles" -> triangles=false; "components" -> components=false; "build" -> inBuild=false } }
        }
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler=handler
        reader.parse(InputSource(bytes.inputStream()))
        return Document(unit,objects,build)
    }
    private fun read3mf(zip: ZipFile): Mesh {
        val documents=mutableMapOf<String,Document>(); var expandedBytes=0
        fun document(path:String):Document = documents.getOrPut(path) {
            val entry=zip.getEntry(path) ?: error(tr("Не найдена геометрия {0}" ,path))
            val bytes=zip.getInputStream(entry).use { it.limited(MAX_XML_BYTES-expandedBytes) }; expandedBytes+=bytes.size
            parse(bytes)
        }
        val main=rootModelPath(zip) ?: error(tr("В 3MF нет геометрии")); val root=document(main); val result=Floats();var references=0
        fun emit(path:String, ref:Reference, matrices:List<FloatArray>, visited:Set<String>) {
            require(visited.size<32) { tr("Слишком глубокая вложенность 3MF") }
            require(++references<=10000) { tr("Слишком много компонентов 3MF") }
            val next=ref.path?.let { raw ->
                require(!raw.contains('\\') && !raw.contains(':')) { tr("Недопустимый путь 3MF") }
                val resolved=if(raw.startsWith('/')) raw.drop(1) else path.substringBeforeLast('/')+"/"+raw
                java.nio.file.Paths.get(resolved).normalize().toString().replace('\\','/').also { require(!it.startsWith("../")) }
            } ?: path
            val key="${next}#${ref.id}"; require(key !in visited) { tr("Цикл компонентов 3MF") }
            val doc=document(next); require(doc.unit==root.unit) { tr("Единицы вложенных моделей 3MF должны совпадать") }
            val mesh=doc.objects[ref.id] ?: error(tr("Нет объекта {0}" ,ref.id))
            val transforms=listOf(ref.transform)+matrices
            for(triangle in mesh.triangles) for(index in triangle) {
                require(index>=0 && index<mesh.vertices.size/3) { tr("Индекс вершины вне модели") }
                var x=mesh.vertices[index*3]; var y=mesh.vertices[index*3+1]; var z=mesh.vertices[index*3+2]
                for(m in transforms) { val nx=x*m[0]+y*m[3]+z*m[6]+m[9]; val ny=x*m[1]+y*m[4]+z*m[7]+m[10]; val nz=x*m[2]+y*m[5]+z*m[8]+m[11]; x=nx;y=ny;z=nz }
                result.add(x*root.unit);result.add(y*root.unit);result.add(z*root.unit)
                require(result.size<=MAX_TRIANGLES*9) { tr("Модель содержит больше миллиона треугольников") }
            }
            mesh.components.forEach { emit(next,it,transforms,visited+key) }
        }
        require(root.build.isNotEmpty()) { tr("В 3MF нет печатаемых объектов") }
        root.build.forEach { emit(main,it,emptyList(),emptySet()) }
        return Mesh(result.array()).also { require(!it.isEmpty) { tr("В 3MF нет геометрии") } }
    }
    private class Floats {
        private var data=FloatArray(4096);var size=0;private set
        operator fun get(i:Int)=data[i]
        fun add(value:Float) { require(value.isFinite()) { tr("Недопустимая координата") }; if(size==data.size) data=data.copyOf(data.size*2);data[size++]=value }
        fun array()=data.copyOf(size)
    }
}
