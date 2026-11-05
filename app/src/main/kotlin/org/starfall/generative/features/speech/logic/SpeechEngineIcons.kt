package org.starfall.generative.features.speech.logic

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.iconMatchName

/** A stable public homepage for the four dedicated speech APIs. */
fun SpeechEngine.brandHomepage(): String? = when (this) {
    SpeechEngine.ELEVENLABS -> "https://elevenlabs.io"
    SpeechEngine.FISH_AUDIO -> "https://fish.audio"
    SpeechEngine.CARTESIA -> "https://cartesia.ai"
    SpeechEngine.SPEECHIFY -> "https://speechify.ai"
    else -> null
}

/** Lazily populate the app's shared icon catalog from official speech-service homepages. */
suspend fun ensureSpeechEngineIcons(context: Context) = coroutineScope {
    val store = IconStore(context.applicationContext)
    SpeechEngine.entries.forEach { engine ->
        val homepage = engine.brandHomepage() ?: return@forEach
        launch { store.ensureNamedFavicon(requireNotNull(engine.iconMatchName), homepage) }
    }
}

/** Application-lifetime requests are not cancelled when the user closes a sheet or editor. */
private object SpeechIconWarmup {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val active = ConcurrentHashMap.newKeySet<String>()
}

fun requestSpeechEngineIcons(context: Context) {
    val appContext = context.applicationContext
    val key = appContext.filesDir.path
    if (!SpeechIconWarmup.active.add(key)) return
    SpeechIconWarmup.scope.launch {
        try {
            ensureSpeechEngineIcons(appContext)
        } finally {
            SpeechIconWarmup.active.remove(key)
        }
    }
}
