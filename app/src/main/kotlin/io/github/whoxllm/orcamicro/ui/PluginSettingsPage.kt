package io.github.whoxllm.orcamicro.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.slicing.PluginCapabilityInfo
import io.github.whoxllm.orcamicro.core.slicing.PluginPermission
import io.github.whoxllm.orcamicro.data.InstalledPlugin
import io.github.whoxllm.orcamicro.data.PluginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun jsString(text: String) = JSONObject.quote(text).replace("<", "\\u003c")
private fun Color.css() = "#%06x".format(toArgb() and 0xffffff)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PluginSettingsPage(plugin: InstalledPlugin, cap: PluginCapabilityInfo, repository: PluginRepository,
                               onError: (Exception) -> Unit) {
    val scope = rememberCoroutineScope()
    val id = plugin.state.id
    val network = PluginPermission.NETWORK_HTTP in plugin.state.permissions.effective()
    val colors = MaterialTheme.colorScheme
    val css = """:root { --orca-fg: ${colors.onSurface.css()}; --orca-bg: ${colors.surface.css()};
        --orca-border: ${colors.outline.css()}; --orca-muted: ${colors.onSurfaceVariant.css()};
        --orca-accent: ${colors.primary.css()}; --orca-accent-fg: ${colors.onPrimary.css()}; --orca-font: system-ui; }"""
    var revision by remember { mutableIntStateOf(0) }
    val current = plugin.state.configs[cap.name] ?: cap.defaultConfig
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(onClick = {
            scope.launch {
                try {
                    repository.edit { resetConfig(id, cap.name) }
                    revision++
                } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e) }
            }
        }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(tr("Сбросить настройки по умолчанию")) }
        key(id, cap.name, cap.configUi, css, network, revision) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = !network
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    val page = this
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (request.isForMainFrame && request.url.scheme == "data") return null
                            if (network && request.url.scheme in setOf("http", "https")) return null
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onJsPrompt(view: WebView, url: String?, message: String?, value: String?,
                                                result: JsPromptResult): Boolean {
                            result.cancel()
                            if (message != "orca-save" && message != "orca-reset") return true
                            scope.launch {
                                try {
                                    val saved = repository.edit {
                                        if (message == "orca-reset") resetConfig(id, cap.name)
                                        else saveConfig(id, cap.name, requireNotNull(value))
                                    }
                                    val config = saved.configs[cap.name] ?: cap.defaultConfig
                                    if (page.isAttachedToWindow) page.evaluateJavascript("window.orcaUpdated(" + jsString(config) + ")", null)
                                } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e) }
                            }
                            return true
                        }
                    }
                    val hosts = if (network) " http: https:" else ""
                    val policy = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; " +
                        "script-src 'unsafe-inline'$hosts; style-src 'unsafe-inline'$hosts; img-src data:$hosts; " +
                        "font-src data:$hosts; media-src data:$hosts; connect-src " + if (network) "http: https:" else "'none'"
                    val bridge = """
                        <meta http-equiv="Content-Security-Policy" content="$policy">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>$css</style><script>
                        (() => {
                            let config = JSON.parse(${jsString(current)});
                            const callbacks = [];
                            const copy = () => JSON.parse(JSON.stringify(config));
                            window.orcaUpdated = raw => {
                                config = JSON.parse(raw);
                                callbacks.forEach(callback => callback(copy()));
                            };
                            window.orca = {
                                getConfig: copy,
                                saveConfig: obj => { prompt('orca-save', JSON.stringify(obj)); },
                                restoreDefaults: () => { prompt('orca-reset', ''); },
                                onConfig: callback => { callbacks.push(callback); callback(copy()); },
                                getContext: () => ({scope: 'global', readOnly: false})
                            };
                        })();
                        </script>
                    """.trimIndent()
                    val html = "<!doctype html>" + bridge + cap.configUi.orEmpty()
                    loadData(Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP), "text/html", "base64")
                }
            }, onRelease = { it.stopLoading(); it.webChromeClient = null; it.destroy() })
        }
    }
}
