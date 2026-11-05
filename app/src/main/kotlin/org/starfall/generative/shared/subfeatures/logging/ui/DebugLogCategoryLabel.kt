package org.starfall.generative.shared.subfeatures.logging.ui

import org.starfall.generative.R
import org.starfall.generative.shared.model.logging.DebugLogCategory

fun DebugLogCategory.labelRes(): Int = when (this) {
    DebugLogCategory.NETWORK -> R.string.debug_log_category_network
    DebugLogCategory.SYSTEM -> R.string.debug_log_category_system
}
