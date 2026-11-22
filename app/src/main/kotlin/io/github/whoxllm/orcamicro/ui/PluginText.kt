package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.slicing.PluginMessage

internal fun pluginTrustText() = tr("Плагины запускают код Python своих авторов. Для доступа к интернету и вашим файлам им нужно разрешение. Устанавливайте только плагины, которым доверяете.")

internal fun permissionLabel(name: String) = when (name) {
    "fs_read" -> tr("Чтение файлов")
    "fs_readwrite" -> tr("Чтение и запись файлов")
    "network_http" -> tr("Интернет")
    "network_socket" -> tr("Сетевые сокеты")
    "process" -> tr("Запуск программ")
    "threading" -> tr("Фоновые потоки")
    "events" -> tr("События приложения")
    "native" -> tr("Загрузка машинного кода")
    else -> name
}

internal fun permissionDetail(name: String) = when (name) {
    "fs_read" -> tr("Читать файлы за пределами папок плагина и нарезки.")
    "fs_readwrite" -> tr("Читать, создавать, изменять и удалять файлы вне защищённых папок.")
    "network_http" -> tr("Отправлять HTTP- и HTTPS-запросы.")
    "network_socket" -> tr("Открывать сетевые соединения напрямую.")
    "process" -> tr("Запускать другие программы на устройстве.")
    "threading" -> tr("Выполнять работу в дополнительных потоках.")
    "events" -> tr("Получать события приложения. В этой версии события не передаются.")
    else -> name
}

internal fun pluginWarnings(messages: List<PluginMessage>, names: Map<String, String>) = messages.mapNotNull {
    val name = names[it.pluginId] ?: it.pluginId
    when (it.status) {
        "blocked" -> if (it.permission == "network_http") tr("{0} попытался подключиться к интернету. Заблокировано.", name)
            else tr("{0} запросил разрешение «{1}». Заблокировано.", name, it.permission?.let(::permissionLabel) ?: it.step)
        "error" -> tr("Ошибка плагина {0}: {1}", name, it.message)
        else -> null
    }
}.distinct()
