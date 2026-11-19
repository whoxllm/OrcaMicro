package io.github.whoxllm.orcamicro.core.gcode

import java.io.File
import java.util.Base64

data class GcodeThumbnail(val width:Int, val height:Int, val png:ByteArray)

/** OrcaSlicer PNG comments: base64 character count, 78-character rows and explicit block markers. */
object GcodeThumbnails {
    fun embed(gcode:File, thumbnails:List<GcodeThumbnail>) {
        require(thumbnails.isNotEmpty())
        val header=buildString {
            thumbnails.forEach { image ->
                require(image.width in 1..1024 && image.height in 1..1024 && image.png.size in 8..4*1024*1024)
                require(image.png.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
                val encoded=Base64.getEncoder().encodeToString(image.png)
                append("; THUMBNAIL_BLOCK_START\n\n;\n; thumbnail begin ${image.width}x${image.height} ${encoded.length}\n")
                encoded.chunked(78).forEach { append("; ").append(it).append('\n') }
                append("; thumbnail end\n; THUMBNAIL_BLOCK_END\n\n")
            }
        }.toByteArray(Charsets.US_ASCII)
        val tmp=File(gcode.path+".thumbnail.tmp")
        try {
            gcode.inputStream().buffered().use { input -> tmp.outputStream().buffered().use { output ->
                // Keep Orca's whole identification/metadata block ahead of the images so host slicer detection works.
                fun copyLine():String {
                    val line=java.io.ByteArrayOutputStream()
                    while(true) {
                        val byte=input.read()
                        if(byte<0)break
                        line.write(byte)
                        if(byte=='\n'.code)break
                        require(line.size()<16384) { "Invalid G-code header" }
                    }
                    output.write(line.toByteArray())
                    return line.toString(Charsets.US_ASCII.name())
                }
                val first=copyLine()
                require(first.endsWith('\n')) { "Invalid G-code header" }
                if(first.trim()=="; HEADER_BLOCK_START") {
                    var bytes=first.length
                    while(true) {
                        val line=copyLine();bytes+=line.length
                        require(line.isNotEmpty() && bytes<128*1024) { "Incomplete G-code header" }
                        if(line.trim()=="; HEADER_BLOCK_END")break
                    }
                }
                // Stream the rest, including every print command, byte for byte.
                output.write(header)
                input.copyTo(output)
            } }
            check(tmp.renameTo(gcode)) { "Could not replace G-code with thumbnail output" }
        } finally { tmp.delete() }
    }
}
