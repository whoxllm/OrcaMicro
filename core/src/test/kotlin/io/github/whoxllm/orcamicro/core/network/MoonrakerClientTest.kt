package io.github.whoxllm.orcamicro.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MoonrakerClientTest {
    @Test fun `addresses normalize and web UI defaults to port 80 for direct Moonraker port`() {
        val c = PrinterConnection("printer.local:7125/").normalized()
        assertEquals("http://printer.local:7125", c.apiUrl)
        assertEquals("http://printer.local", c.webUrl)
        assertEquals("https://printer.local/fluidd", PrinterConnection("https://printer.local/api", "https://printer.local/fluidd/").normalized().webUrl)
        for (bad in listOf("file:///tmp/test", "http://user:password@host", "javascript:alert(1)", "http://", "http://host/?apikey=x")) {
            assertFailsWith<IllegalArgumentException>(bad) { PrinterConnection(bad).normalized() }
        }
    }

    @Test fun `upload streams exact G-code to gcodes with API key and printing disabled`() {
        val received = AtomicReference<String>()
        val key = AtomicReference<String>()
        val method = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/upload") { exchange ->
            received.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            key.set(exchange.requestHeaders.getFirst("X-Api-Key")); method.set(exchange.requestMethod)
            val response = """{"result":{"item":{"path":"cube.gcode"},"print_started":false}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val file = Files.createTempFile("orca-upload-", ".gcode").toFile()
        val content = "; sample\nG28\n" + "G1 X1 Y2 E0.1\n".repeat(12000)
        file.writeText(content)
        try {
            val progress = mutableListOf<Int>()
            val result = MoonrakerClient(PrinterConnection("http://127.0.0.1:${server.address.port}", apiKey = "test-key")).upload(file, "cube.gcode") { progress.add(it) }
            assertEquals("cube.gcode", result)
            assertEquals("POST", method.get()); assertEquals("test-key", key.get())
            val body = received.get()
            assertTrue(body.contains("name=\"root\"\r\n\r\ngcodes\r\n"))
            assertTrue(body.contains("name=\"print\"\r\n\r\nfalse\r\n"))
            assertTrue(body.contains("filename=\"cube.gcode\"")); assertTrue(body.contains(content))
            assertEquals(0, progress.first()); assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        } finally { server.stop(0); file.delete() }
    }

    @Test fun `connection status parses ready and rejected authorization is explained`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/info") { e ->
            if (e.requestHeaders.getFirst("X-Api-Key") == "ok") {
                val body = """{"result":{"klippy_state":"ready"}}""".toByteArray()
                e.sendResponseHeaders(200, body.size.toLong()); e.responseBody.use { it.write(body) }
            } else { e.sendResponseHeaders(403, -1); e.close() }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            assertEquals("Klipper готов", MoonrakerClient(PrinterConnection(url, apiKey = "ok")).info())
            val failure = assertFailsWith<IllegalStateException> { MoonrakerClient(PrinterConnection(url)).info() }
            assertTrue(failure.message.orEmpty().contains("API-ключ"))
        } finally { server.stop(0) }
    }
}
