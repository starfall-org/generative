package org.starfall.generative

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.MigratedStringPreferences
import org.starfall.generative.shared.state.preferences.ApplicationPreferencesOwner
import org.starfall.generative.shared.state.preferences.StringPreferences

/** Synthetic ComponentActivity tests need rendering, not production repositories or startup migrations. */
class MigrationUiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, MigrationUiApplication::class.java.name, context)
}

class MigrationUiApplication : Application(), ApplicationPreferencesOwner, coil3.SingletonImageLoader.Factory {
    override val appPreferences by lazy { AppPreferencesRepository(this).appPreferencesFlow }
    override fun stringPreferences(namespace: String): StringPreferences =
        MigratedStringPreferences.get(this, namespace)

    override fun newImageLoader(context: Context): coil3.ImageLoader =
        org.starfall.generative.shared.subfeatures.icons.logic.AppImages.createLoader(context)
}
