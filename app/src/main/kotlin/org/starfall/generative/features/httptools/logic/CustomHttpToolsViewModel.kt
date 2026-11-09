package org.starfall.generative.features.httptools.logic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.starfall.generative.database.repositories.CustomHttpToolRepository
import org.starfall.generative.shared.model.CustomHttpTool

class CustomHttpToolsViewModel(private val repository: CustomHttpToolRepository) : ViewModel() {
    val tools = repository.tools.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    suspend fun save(tool: CustomHttpTool) = repository.save(tool)
    fun setEnabled(id: String, enabled: Boolean) = repository.setEnabled(id, enabled)
    fun delete(id: String) = repository.delete(id)
}
