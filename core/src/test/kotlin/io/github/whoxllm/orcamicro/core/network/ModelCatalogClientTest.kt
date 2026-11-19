package io.github.whoxllm.orcamicro.core.network

import kotlinx.serialization.json.*
import kotlin.test.*

class ModelCatalogClientTest {
    private fun json(text:String)=Json.parseToJsonElement(text)
    private val printable="""{"id":"3161","name":"3D BENCHY","slug":"3d-benchy","premium":false,"price":null,"image":{"filePath":"media/prints/3161/benchy.jpg"},"user":{"publicUsername":"Prusa Research","handle":"Prusa3D"},"license":{"name":"Public Domain"},"tags":[{"name":"benchy"}],"stls":[{"id":"49068","name":"3dbenchy.stl"}]}"""

    @Test fun `Printables search pagination details and download use native API contracts`() {
        val calls=mutableListOf<String>()
        val client=ModelCatalogClient { url,body,headers->
            assertEquals(ModelCatalogClient.PRINTABLES,url);assertTrue(headers.isEmpty())
            val query=body!!["query"]!!.jsonPrimitive.content;calls+=query
            when {
                query.contains("searchPrints2") -> {
                    assertEquals("benchy boat",body["variables"]!!.jsonObject["q"]!!.jsonPrimitive.content)
                    json("""{"data":{"searchPrints2":{"items":[$printable],"totalCount":2}}}""")
                }
                query.contains("getDownloadLink") -> {
                    val vars=body["variables"]!!.jsonObject
                    assertEquals("49068",vars["id"]!!.jsonPrimitive.content);assertEquals("3161",vars["print"]!!.jsonPrimitive.content)
                    json("""{"data":{"getDownloadLink":{"ok":true,"output":{"link":"https://files.printables.com/model.stl?key=short-lived"},"errors":null}}}""")
                }
                else -> json("""{"data":{"print":$printable}}""")
            }
        }
        val page=client.search(ModelSource.PRINTABLES,"benchy",listOf("boat"))
        assertEquals(1,page.nextOffset);assertEquals(2,page.total)
        val model=page.models.single()
        assertEquals("Prusa Research",model.author);assertEquals("Public Domain",model.license);assertEquals(false,model.paid)
        assertEquals("https://media.printables.com/media/prints/3161/thumbs/inside/640x480/jpg/benchy.webp",model.imageUrl)
        val details=client.details(model.url)
        assertEquals("3dbenchy.stl",details.files.single().name)
        assertEquals("https://files.printables.com/model.stl?key=short-lived",client.downloadUrl(details.model,details.files.single()))
        assertEquals(3,calls.size)
    }
    @Test fun `MakerWorld uses design2 search and never mistakes a license for a price`() {
        val client=ModelCatalogClient { url,_,_->
            assertTrue(url.contains("/search-service/select/design2?keyword=phone%20holder&"))
            json("""{"total":1,"hits":[{"id":12,"title":"Phone holder","slug":"phone-holder","cover":"https://makerworld.bblmw.com/12.jpg","designCreator":{"name":"Maker"},"license":"BY-NC","tags":["phone"]}]}""")
        }
        val model=client.search(ModelSource.MAKERWORLD,"phone holder").models.single()
        assertEquals("Maker",model.author);assertEquals("BY-NC",model.license);assertNull(model.paid)
        val detailed=ModelCatalogClient.decode(ModelSource.MAKERWORLD,json("""{"id":12,"title":"Phone holder","paidSetting":{"isPaid":true},"license":"BY-NC"}"""))!!
        assertEquals(true,detailed.paid)
    }
    @Test fun `Cults uses personal Basic auth and clearly exposes that files are unavailable`() {
        val client=ModelCatalogClient(CatalogCredentials(cultsUser="maker",cultsKey="fixture")) { url,body,headers->
            assertEquals(ModelCatalogClient.CULTS,url);assertTrue(headers["Authorization"]!!.startsWith("Basic "))
            assertTrue(body!!["query"]!!.jsonPrimitive.content.contains("creation(slug:"))
            json("""{"data":{"creation":{"name":"Cat","url":"https://cults3d.com/en/3d-model/art/cat","illustrationImageUrl":"https://images.cults3d.com/cat.jpg","license":{"name":"Cults Private"},"tags":["cat"],"price":{"cents":250},"creator":{"nick":"Sculptor"}}}}""")
        }
        val detail=client.details("https://cults3d.com/en/3d-model/art/cat")
        assertEquals("Sculptor",detail.model.author);assertEquals(true,detail.model.paid);assertEquals("2.50 USD",detail.model.priceLabel)
        assertTrue(detail.files.isEmpty());assertTrue(detail.notice!!.contains("не предоставляет файлы"))
    }
    @Test fun `Thingiverse lists supported files with user token and rejects absent credentials`() {
        assertFailsWith<IllegalArgumentException> { ModelCatalogClient { _,_,_->error("Must not request without token") }.search(ModelSource.THINGIVERSE,"cube") }
        val client=ModelCatalogClient(CatalogCredentials(thingiverseToken="fixture")) { url,_,headers->
            assertEquals("Bearer fixture",headers["Authorization"])
            if(url.endsWith("/files"))json("""[{"id":10,"name":"cube.stl","download_url":"https://api.thingiverse.com/files/10/download"},{"id":11,"name":"readme.pdf","download_url":"https://cdn.thingiverse.com/readme.pdf"}]""")
            else json("""{"id":5,"name":"Cube","thumbnail":"https://cdn.thingiverse.com/cube.jpg","creator":{"name":"Designer"},"license":"cc"}""")
        }
        val details=client.details("https://www.thingiverse.com/thing:5")
        assertEquals("Designer",details.model.author);assertEquals(false,details.model.paid);assertEquals(1,details.files.size)
    }
    @Test fun `tokens cannot escape their provider through download redirects or header injection`() {
        val credentials=CatalogCredentials("thing-key","user","cults-key","sessionid=print-key","bambu-key")
        val client=ModelCatalogClient(credentials)
        assertEquals("Bearer thing-key",client.headers("https://api.thingiverse.com/files/1/download")["Authorization"])
        assertEquals("Bearer bambu-key",client.headers("https://api.bambulab.com/v1/design-service")["Authorization"])
        assertEquals("sessionid=print-key",client.headers(ModelCatalogClient.PRINTABLES)["Cookie"])
        for(url in listOf("https://files.printables.com/a.stl","https://api.thingiverse.com.evil.example/a","http://api.thingiverse.com/a","https://evil.example/a","https://user@api.bambulab.com/a"))assertTrue(client.headers(url).isEmpty(),url)
        assertFails { ModelCatalogClient(credentials.copy(thingiverseToken="key\r\nX-Injected: value")) }
    }
    @Test fun `malformed links API errors and refused downloads do not become printable models`() {
        for(source in ModelSource.entries)assertFails { ModelCatalogClient.modelId(source,source.home) }
        assertFails { ModelCatalogClient.modelId(ModelSource.MAKERWORLD,"https://makerworld.com.evil.example/en/models/12") }
        assertEquals("3161",ModelCatalogClient.modelId(ModelSource.PRINTABLES,"https://www.printables.com/model/3161-3d-benchy/files"))
        assertEquals("12",ModelCatalogClient.modelId(ModelSource.MAKERWORLD,"https://makerworld.com/en/models/12-cube?from=search"))
        assertFails { ModelCatalogClient { _,_,_->json("""{"errors":[{"message":"changed schema"}]}""") }.search(ModelSource.PRINTABLES,"x") }
        val denied=ModelCatalogClient { _,_,_->json("""{"data":{"getDownloadLink":{"ok":false,"output":null}}}""") }
        assertFails { denied.downloadUrl(LibraryModel(source=ModelSource.PRINTABLES,url="https://www.printables.com/model/1",title="Locked",remoteId="1"),CatalogFile("1","locked.stl",kind="printables")) }
    }
    @Test fun `old saved cards decode with unknown attribution and price`() {
        val card=Json.decodeFromString<LibraryModel>("""{"source":"PRINTABLES","url":"https://www.printables.com/model/1","title":"Old card"}""")
        assertNull(card.author);assertNull(card.license);assertNull(card.paid);assertNull(card.remoteId)
    }
}
