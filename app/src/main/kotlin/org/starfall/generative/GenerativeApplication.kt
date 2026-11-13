package org.starfall.generative

import org.starfall.generative.shared.state.preferences.ApplicationPreferencesOwner
import org.starfall.generative.shared.state.preferences.StringPreferences
import org.starfall.generative.database.preferences.MigratedStringPreferences
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.starfall.generative.appwiring.AppContainer
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.RoomOAuthRecordStorage
import org.starfall.generative.integrations.security.OAuthRecordStorage
import org.starfall.generative.integrations.security.OAuthRecordStorageOwner
import org.starfall.generative.integrations.remote.RemoteConnectionService
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.remote.RemoteMachineServiceOwner
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashReportStore
import org.starfall.generative.shared.subfeatures.crashreports.logic.CrashRecordingHandler
import org.starfall.generative.shared.subfeatures.auth.logic.OAuthReturnIntentProvider
import java.io.File
import org.starfall.generative.features.home.chat.ui.ChatRenderCaches
import org.starfall.generative.shared.model.ToolPayloadCache

class GenerativeApplication : Application(), ApplicationPreferencesOwner, RemoteMachineServiceOwner, OAuthRecordStorageOwner,
    OAuthReturnIntentProvider, coil3.SingletonImageLoader.Factory {
    override val appPreferences by lazy { container.preferences.appPreferencesFlow }
    override fun stringPreferences(namespace: String): StringPreferences =
        MigratedStringPreferences.get(this, namespace)

    override fun createOAuthReturnIntent(context: android.content.Context): android.content.Intent =
        android.content.Intent(context, MainActivity::class.java)

    override val oauthRecordStorage: OAuthRecordStorage by lazy {
        RoomOAuthRecordStorage(this, AppDatabase.getInstance(this))
    }

    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        org.starfall.generative.shared.subfeatures.icons.logic.AppImages.createLoader(context)

    val container by lazy { AppContainer(this) }

    /** Shared with the SFTP DocumentsProvider, which Android may create before the UI. */
    override val remoteMachineService: RemoteMachineService get() = container.remoteMachineService
    internal val crashReports by lazy {
        CrashReportStore(File(filesDir, "last_crash.json"), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashRecordingHandler(crashReports, Thread.getDefaultUncaughtExceptionHandler())
        )
        // The chat render caches are a convenience only: give the RAM back as soon as Android asks.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                ChatRenderCaches.onTrimMemory(level)
                if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
                    ToolPayloadCache.clear()
                }
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() {
                ChatRenderCaches.clearAll()
                ToolPayloadCache.clear()
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
        applicationScope.launch { container.defaultDataInitializer.initialize() }
        // Off the main thread: the first access builds the machine store. One notification per
        // connected machine (with a Disconnect button) is owned by RemoteConnectionService.
        applicationScope.launch(Dispatchers.Default) {
            remoteMachineService.connectedMachineIds.collect { ids ->
                if (ids.isNotEmpty()) RemoteConnectionService.start(this@GenerativeApplication)
            }
        }
    }
}
