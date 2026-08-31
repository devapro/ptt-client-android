# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Currently dormant: app/build.gradle.kts has isMinifyEnabled = false for the release build type,
# so R8 does not run and this rule has no effect. Required again if and when minification is
# enabled.
#
# Ktor's client-core carries a debugger-presence check (io.ktor.util.debug.IntellijIdeaDebugDetector)
# that references java.lang.management.ManagementFactory/RuntimeMXBean — real JVM classes that do
# not exist on Android and are never reached at runtime here (the check is JVM/desktop-only; on
# Android it just returns false). R8 still sees the reference at the bytecode level and refuses to
# proceed without being told the missing classes are expected. Discovered running :app:assembleRelease
# with isMinifyEnabled = true (Phase 8's F-Droid reproducibility check); rules as suggested by
# app/build/outputs/mapping/release/missing_rules.txt.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Currently dormant: app/build.gradle.kts has isMinifyEnabled = false for the release build type,
# so R8 does not run and this rule has no effect. Required again if and when minification is
# enabled.
#
# WorkManager (a transitive dependency, pulled in by androidx.datastore/androidx.glance — this
# app never calls WorkManager itself) auto-initializes androidx.work.impl.WorkManagerImpl via a
# ContentProvider (androidx.startup.InitializationProvider), which builds a Room database
# (WorkDatabase) reflectively: androidx.room.util.KClassUtil.findAndInstantiateDatabaseImpl looks
# up "<canonical name>_Impl" with Class.forName and calls getDeclaredConstructor().newInstance().
# work-runtime's own consumer rule (-keep class * extends androidx.room.RoomDatabase { void
# <init>(); }) only protects the no-arg constructor; it is not enough to stop R8 from otherwise
# touching WorkDatabase_Impl (observed under isMinifyEnabled = true as an app launch crash:
# "Failed to create an instance of androidx.work.impl.WorkDatabase", cause InstantiationException
# — the exact exception KClassUtil.findAndInstantiateDatabaseImpl catches when the reflective
# newInstance() fails). Keeping the whole class, not just its constructor, fixes it.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }