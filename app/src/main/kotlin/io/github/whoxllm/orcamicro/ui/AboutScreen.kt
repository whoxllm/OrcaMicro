package io.github.whoxllm.orcamicro.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.BuildConfig

private val thirdParty = listOf(
    "OrcaSlicer (libslic3r), AGPL-3.0, by SoftFever and contributors, based on Bambu Studio, PrusaSlicer and Slic3r",
    "Boost, Boost Software License 1.0",
    "oneTBB, Apache-2.0",
    "CGAL, GPL-3.0 / LGPL-3.0",
    "Eigen, MPL-2.0",
    "Open CASCADE Technology, LGPL-2.1 with exception",
    "OpenCV, Apache-2.0",
    "GMP and MPFR, LGPL-3.0",
    "NLopt, MIT / LGPL-2.1",
    "Draco, Apache-2.0",
    "libnoise, LGPL-2.1",
    "cereal, BSD-3-Clause",
    "expat, MIT",
    "libpng, PNG Reference Library License",
    "libjpeg-turbo, IJG / BSD-3-Clause",
    "FreeType, FreeType License",
    "Clipper and Clipper2, Boost Software License 1.0",
    "Qhull, Qhull License",
    "admesh, libigl, mcut, miniz, nlohmann/json and the other libraries bundled with OrcaSlicer",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization, AndroidX and Jetpack Compose, Apache-2.0",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About OrcaMicro") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OrcaMicro ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text("Slicing engine: OrcaSlicer ${BuildConfig.ORCA_VERSION}, running entirely on this phone.")
            Text(
                "OrcaMicro is free software: you can redistribute it and/or modify it under the terms of the " +
                    "GNU Affero General Public License version 3 or later. It comes with no warranty. " +
                    "Its complete source code is available from the project repository.",
            )
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL)))
                    } catch (e: ActivityNotFoundException) {
                        // No browser installed; nothing to do.
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Source code") }
            Text("Built with", style = MaterialTheme.typography.titleMedium)
            for (line in thirdParty) Text("• $line", style = MaterialTheme.typography.bodySmall)
            Text(
                "Printer, process and filament profiles come from OrcaSlicer and its contributors.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
