package com.github.devapro.pttdroid

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

class CoroutineContextProvider {

    val main: CoroutineDispatcher by lazy { Dispatchers.Main }

    val io: CoroutineDispatcher by lazy { Dispatchers.IO }

    val default: CoroutineDispatcher by lazy { Dispatchers.Default }

    val globalScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob(null) + main) }

    fun createScope(
        context: CoroutineContext
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob(null) + context)
    }
}