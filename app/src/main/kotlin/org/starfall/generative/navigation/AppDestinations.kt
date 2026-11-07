package org.starfall.generative.navigation

import android.net.Uri
import org.starfall.generative.shared.model.media.MediaKind

/** Distinct NavHost destinations, not conditional pages inside Feature Tools. */
internal object MediaProfileRoutes {
    const val LIBRARY = "system_tools/profiles/{kind}"
    const val EDITOR = "system_tools/profiles/{kind}/edit/{profileId}"
    fun library(kind: MediaKind) = "system_tools/profiles/${kind.name.lowercase()}"
    fun editor(kind: MediaKind, profileId: String) = "${library(kind)}/edit/${Uri.encode(profileId)}"
    fun library(video: Boolean) = "system_tools/profiles/${if (video) "video" else "image"}"
    fun editor(video: Boolean, profileId: String) =
        "${library(video)}/edit/${Uri.encode(profileId)}"
}


enum class AppDestination(val route: String) {
    CHAT("chat"),
    PROVIDERS("providers"),
    MCP("mcp"),
    SPEECH("speech"),
    FEATURES("features"),
    CUSTOMIZATION("user_profile"),
    CHAT_OPTIONS("chat_options"),
    TOOLS_EDITOR("system_tools"),
    STORAGE("storage"),
    REMOTE_MACHINES("remote_machines"),
    MENU("menu"),
    APP_SETTINGS("AppSettings"),
    DEVELOPER_OPTIONS("developer_options"),
    LAUNCHER_DRAG_TEST("launcher_drag_test"),
    PROVIDERS_DEMO("providers_demo"),
    ICONS("Icons"),
    APP_DATA("AppData"),
    ABOUT("About"),
}
