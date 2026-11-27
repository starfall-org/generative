package org.starfall.generative.appwiring

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.shared.logic.ChatBackgroundService

/** Activities and active requests share the same chat state until both have let it go. */
internal class ChatSession(
    context: Context,
    createChat: ((Job) -> Unit) -> ChatViewModel,
) : ViewModelStoreOwner {
    private val factory = viewModelFactory { initializer { createChat(::trackRequest) } }
    override val viewModelStore = ViewModelStore()
    private val app = context.applicationContext
    private val service = Intent(app, ChatBackgroundService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screens = 0
    private var requests = 0
    private var foregroundRequested = false
    private var progressObserver: Job? = null
    private var idleStop: Job? = null

    val isWorking get() = requests > 0

    fun attach(): ChatViewModel {
        val chat = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        screens++
        if (progressObserver == null) {
            progressObserver = scope.launch {
                chat.summaryProgress.collect { ChatBackgroundService.showSummaryProgress(it) }
            }
        }
        return chat
    }

    fun detach(chat: ChatViewModel) {
        screens--
        if (screens == 0) chat.stopSpeaking()
        releaseIfIdle()
    }

    /** Called on Main by each accepted request before it contacts the provider. */
    fun trackRequest(job: Job) {
        idleStop?.cancel()
        idleStop = null
        requests++
        if (!foregroundRequested) retryForegroundStart()
        job.invokeOnCompletion {
            scope.launch {
                requests--
                if (!isWorking) {
                    // Queued messages and automatic summaries can follow a completed reply in the background.
                    idleStop = scope.launch {
                        delay(5_000L)
                        if (foregroundRequested) app.stopService(service)
                        foregroundRequested = false
                        idleStop = null
                        releaseIfIdle()
                    }
                }
            }
        }
    }

    fun retryForegroundStart() {
        // A previous start can be accepted here and rejected later in Service.onStartCommand.
        // Returning to a visible activity gives that active request another chance.
        if (!isWorking) return
        try {
            ContextCompat.startForegroundService(app, service)
            foregroundRequested = true
        } catch (error: IllegalStateException) {
            Log.w("ChatBackgroundService", "System refused foreground service start", error)
        } catch (error: SecurityException) {
            Log.w("ChatBackgroundService", "Foreground service permission unavailable", error)
        }
    }

    private fun releaseIfIdle() {
        if (screens != 0 || isWorking || idleStop != null) return
        progressObserver?.cancel()
        progressObserver = null
        viewModelStore.clear()
    }
}

/** The activity's retained handle releases only its own interest when its task is removed. */
internal class ChatSessionHandle(private val session: ChatSession) : ViewModel() {
    val chat = session.attach()
    val isWorking get() = session.isWorking
    fun retryForegroundStart() = session.retryForegroundStart()

    override fun onCleared() {
        session.detach(chat)
    }
}
