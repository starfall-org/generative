package org.starfall.generative

import org.starfall.generative.shared.state.preferences.ApplicationPreferencesOwner
import org.starfall.generative.shared.state.preferences.StringPreferences
import org.starfall.generative.database.preferences.MigratedStringPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository
import android.app.Application
import android.content.Context
import androidx.room.Room
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.RoomOAuthRecordStorage
import org.starfall.generative.integrations.security.OAuthRecordStorage
import org.starfall.generative.integrations.security.OAuthRecordStorageOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import okhttp3.OkHttp
import org.starfall.generative.shared.subfeatures.icons.logic.AppImages

/** Real image/network assets without production database initialization in each test. */
class LibraryTestApplication : Application(), ApplicationPreferencesOwner, SingletonImageLoader.Factory, OAuthRecordStorageOwner {
    override val appPreferences by lazy { AppPreferencesRepository(this).appPreferencesFlow }
    override fun stringPreferences(namespace: String): StringPreferences =
        MigratedStringPreferences.get(this, namespace)

    private val tokenDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build()
    }
    private val defaultOAuthStorage by lazy { RoomOAuthRecordStorage(this, tokenDatabase) }
    // Tests can point the synchronous OAuth adapter at the very same database as a repository,
    // reproducing nested-transaction deadlocks that separate in-memory stores would miss.
    @Volatile var testOAuthStorage: OAuthRecordStorage? = null
    override val oauthRecordStorage: OAuthRecordStorage
        get() = testOAuthStorage ?: defaultOAuthStorage

    override fun onCreate() {
        super.onCreate()
        OkHttp.initialize(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = AppImages.createLoader(context)
}
