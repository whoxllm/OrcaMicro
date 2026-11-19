package io.github.whoxllm.orcamicro.core.network

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ModelDownloadTest {
    private class Response(val bytes:ByteArray,val length:Long,val html:Boolean=false):HttpURLConnection(URL("https://fixture.invalid/model.stl")) {
        var closed=false
        override fun connect() {}
        override fun disconnect() { closed=true }
        override fun usingProxy()=false
        override fun getResponseCode()=200
        override fun getContentType()=if(html) "text/html" else "application/octet-stream"
        override fun getContentLengthLong()=length
        override fun getInputStream()=ByteArrayInputStream(bytes)
    }
    @Test fun `known and unknown lengths report bytes and disconnect`() {
        val dir=createTempDirectory().toFile()
        try {for(length in listOf(150000L,-1L)) {
            val bytes=ByteArray(150000){(it%251).toByte()};val response=Response(bytes,length)
            val target=dir.resolve("model.bin");val progress=mutableListOf<Pair<Long,Long>>()
            ModelDownload.fetchUsing("https://fixture.invalid/model.stl",target,200000,{emptyMap()},{done,total->progress+=done to total}){response}
            assertContentEquals(bytes,target.readBytes());assertEquals(0L to length,progress.first());assertEquals(150000L to length,progress.last())
            assertTrue(progress.zipWithNext().all{(a,b)->b.first>=a.first});assertTrue(response.closed)
        }} finally {dir.deleteRecursively()}
    }
    @Test fun `truncated oversized and HTML responses leave no cached partial file`() {
        val dir=createTempDirectory().toFile()
        try {for(response in listOf(Response(ByteArray(10),20),Response(ByteArray(30),-1),Response("<html>login</html>".toByteArray(),18,true))) {
            val target=dir.resolve("bad.bin").apply{writeText("previous partial")}
            assertFails {ModelDownload.fetchUsing("https://fixture.invalid/model.stl",target,25,{emptyMap()},{_,_->}){response}}
            assertFalse(target.exists());assertTrue(response.closed)
        }} finally {dir.deleteRecursively()}
    }
}
