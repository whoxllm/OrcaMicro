package io.github.whoxllm.orcamicro.data

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.whoxllm.orcamicro.core.network.CatalogCredentials
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound secrets, deliberately separate from presets, state.json and Android backup. */
class CatalogCredentialStore(context:Context) {
    private val file=File(context.noBackupFilesDir,"catalog-api.enc")
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load():CatalogCredentials {
        if(!file.isFile)return CatalogCredentials()
        val parts=file.readText().split(':',limit=2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return Json.decodeFromString(CatalogCredentials.serializer(),cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).decodeToString())
    }
    fun save(value:CatalogCredentials) {
        value.validate()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encoded=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(Json.encodeToString(CatalogCredentials.serializer(),value).toByteArray()),Base64.NO_WRAP)
        val tmp=File(file.path+".tmp");tmp.writeText(encoded);check(tmp.renameTo(file)) { tr("Не удалось сохранить API-ключи") }
    }
    companion object { private const val ALIAS="orcaslicer-catalog-api-v1" }
}
