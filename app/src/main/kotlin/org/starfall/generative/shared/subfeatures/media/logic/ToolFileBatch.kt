package org.starfall.generative.shared.subfeatures.media.logic

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Tracks only new files from a cancellable generation; pre-existing files are never rolled back. */
internal class ToolFileBatch : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolFileBatch>
    private val created = mutableListOf<Pair<ToolFiles, String>>()
    @Synchronized fun record(store: ToolFiles, name: String) { created += store to name }
    @Synchronized fun rollback() {
        created.forEach { (store, name) -> store.delete(listOf(name)) }
        created.clear()
    }
}
