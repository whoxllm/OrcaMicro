package io.github.whoxllm.orcamicro

import android.app.Application

/** Runs in both the app process and the :engine process, so it must stay free of heavy work. */
class OrcaMicroApplication : Application()
