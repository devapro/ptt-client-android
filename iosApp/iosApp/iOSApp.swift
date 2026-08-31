import SwiftUI
import PTTdroidShared

@main
struct iOSApp: App {

    init() {
        // The real Koin entry point — see di/KoinIos.kt's KDoc. (droid-passwords' iosApp calls a
        // stale `AppDiKt.doInitKoin()` that no longer matches its Kotlin source; this project's
        // function is named to match `initKoinIos()` exactly, verified against
        // shared/src/iosMain/kotlin/com/github/devapro/pttdroid/di/KoinIos.kt in this same change.)
        KoinIosKt.initKoinIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
