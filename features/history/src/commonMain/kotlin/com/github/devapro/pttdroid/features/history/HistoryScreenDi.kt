package com.github.devapro.pttdroid.features.history

import com.github.devapro.pttdroid.features.api.history.HistoryScreenFactory
import com.github.devapro.pttdroid.features.history.reducer.CloseHistoryReducer
import com.github.devapro.pttdroid.features.history.reducer.DeleteRecordReducer
import com.github.devapro.pttdroid.features.history.reducer.OpenHistoryReducer
import com.github.devapro.pttdroid.features.history.reducer.PlayRecordReducer
import com.github.devapro.pttdroid.features.history.reducer.StopRecordPlaybackReducer
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

/**
 * Koin registration for `:features:history` — see `registerMainScreenDi`'s KDoc in
 * `:features:main` for the pattern and for why the view model is a `single` rather than a
 * `viewModelOf`. `HistoryViewModel` mirrors `PttController`'s replaying-record id and the store's
 * records into its state, so a duplicate instance would duplicate both collections for the life of
 * the process; and it is the reason a replay survives leaving the list, so its identity really does
 * have to outlive the screen.
 */
fun Module.registerHistoryScreenDi() {
    factoryOf(::HistoryScreenFactoryImpl) bind HistoryScreenFactory::class
    factoryOf(::OpenHistoryReducer)
    factoryOf(::CloseHistoryReducer)
    factoryOf(::PlayRecordReducer)
    factoryOf(::StopRecordPlaybackReducer)
    factoryOf(::DeleteRecordReducer)
    singleOf(::HistoryViewModel)

    factory {
        HistoryActionProcessor(
            reducers = setOf(
                get<OpenHistoryReducer>(),
                get<CloseHistoryReducer>(),
                get<PlayRecordReducer>(),
                get<StopRecordPlaybackReducer>(),
                get<DeleteRecordReducer>(),
            ),
        )
    }
}
