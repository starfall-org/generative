package org.starfall.generative.shared.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import org.starfall.generative.shared.ui.inputs.ApiKeyField
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate

/** The provider editor renders configuration and an optional model management capability. */
@Composable
internal fun ProviderEditorLayout(
    title: @Composable () -> Unit, onBack: () -> Unit,
    pager: PagerState? = null, models: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    configuration: @Composable ColumnScope.() -> Unit
) {
    EditorScreenTemplate(title = title, onBack = onBack, modifier = Modifier.fillMaxSize(), actions = actions) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (pager != null && models != null) {
                AppSwipeTabs(tabs = listOf(stringResource(R.string.common_configuration), stringResource(R.string.provider_models)),
                    pagerState = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    if (page == 0) ProviderConfigurationForm(configuration) else models()
                }
            } else ProviderConfigurationForm(configuration)
        }
    }
}

@Composable
private fun ProviderConfigurationForm(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().testTag("provider-configuration-form").verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun ProviderCredentialField(
    value: String, onValueChange: (String) -> Unit,
    label: @Composable () -> Unit, visibilityKey: Any?,
    readOnly: Boolean = false, leadingIcon: (@Composable () -> Unit)? = null,
    onFocusLost: (() -> Unit)? = null
) {
    ApiKeyField(value = value, onValueChange = onValueChange, label = label, readOnly = readOnly,
        leadingIcon = leadingIcon, visibilityKey = visibilityKey, maxLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("provider_api_key").then(
            if (onFocusLost != null) Modifier.onFocusChanged { if (!it.isFocused) onFocusLost() } else Modifier
        ))
}
