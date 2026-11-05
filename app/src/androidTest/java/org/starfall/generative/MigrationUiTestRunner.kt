package org.starfall.generative

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Synthetic ComponentActivity tests need rendering, not production repositories or startup migrations. */
class MigrationUiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, MigrationUiApplication::class.java.name, context)
}

class MigrationUiApplication : Application(), coil3.SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): coil3.ImageLoader =
        org.starfall.generative.shared.subfeatures.icons.logic.AppImages.createLoader(context)
}
