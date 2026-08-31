package com.github.devapro.pttdroid

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Unchanged behaviour for Android/desktop — see the `expect val` KDoc in `CoroutineContextProvider.kt`. */
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
