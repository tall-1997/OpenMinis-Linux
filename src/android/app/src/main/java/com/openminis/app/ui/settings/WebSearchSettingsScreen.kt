package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.WebSearchSettings
import com.openminis.app.ui.components.DialogTextField

@Composable
fun WebSearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(WebSearchSettings.engine(context)) }
    var searx by remember { mutableStateOf(WebSearchSettings.searxngUrl(context)) }
    var keys by remember {
        mutableStateOf(
            WebSearchSettings.Engine.entries.filter { it.needsKey }
                .associateWith { WebSearchSettings.apiKey(context, it) },
        )
    }
    var customUrl by remember { mutableStateOf(WebSearchSettings.customUrl(context)) }
    var customKey by remember { mutableStateOf(WebSearchSettings.customKey(context)) }
    var customHeader by remember { mutableStateOf(WebSearchSettings.customKeyHeader(context)) }
    var fallback by remember { mutableStateOf(WebSearchSettings.fallbackEnabled(context)) }
    var detail by remember { mutableStateOf<WebSearchSettings.Engine?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    val title = detail?.let { engineLabel(it) } ?: stringResource(R.string.settings_web_search)

    SettingsScaffold(
        title = title,
        // List is a first-level settings page (no back arrow). Engine
        // detail keeps the arrow to pop back to the list.
        onBack = if (detail != null) {
            { detail = null }
        } else {
            null
        },
    ) {
        val current = detail
        if (current == null) {
            SettingsSection(
                header = stringResource(R.string.web_search_engine_header),
                footer = stringResource(R.string.web_search_engine_footer),
            ) {
                WebSearchSettings.Engine.entries.forEachIndexed { index, item ->
                    val configured = when (item) {
                        WebSearchSettings.Engine.DDG -> true
                        WebSearchSettings.Engine.SEARXNG -> searx.isNotBlank()
                        WebSearchSettings.Engine.CUSTOM -> customUrl.isNotBlank()
                        else -> !keys[item].isNullOrBlank()
                    }
                    EngineNavRow(
                        title = engineLabel(item),
                        subtitle = if (configured) {
                            if (item == WebSearchSettings.Engine.DDG) {
                                stringResource(R.string.web_search_no_key_needed)
                            } else {
                                stringResource(R.string.web_search_configured)
                            }
                        } else {
                            stringResource(R.string.web_search_not_configured)
                        },
                        selected = engine == item,
                        showDivider = index < WebSearchSettings.Engine.entries.lastIndex,
                        onClick = { detail = item },
                    )
                }
            }

            SettingsSection(footer = stringResource(R.string.web_search_fallback_footer)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_search_fallback),
                    subtitle = stringResource(R.string.web_search_fallback_sub),
                    checked = fallback,
                    onCheckedChange = {
                        fallback = it
                        WebSearchSettings.setFallbackEnabled(context, it)
                    },
                    showDivider = false,
                )
            }
        } else {
            SettingsSection(footer = engineDetailFooter(current)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_search_use_engine),
                    checked = engine == current,
                    onCheckedChange = { on ->
                        if (on) {
                            engine = current
                            WebSearchSettings.setEngine(context, current)
                        }
                    },
                    showDivider = current != WebSearchSettings.Engine.DDG,
                )
                when (current) {
                    WebSearchSettings.Engine.DDG -> { }
                    WebSearchSettings.Engine.SEARXNG -> CredentialField(
                        label = stringResource(R.string.web_search_searxng_url),
                        value = searx,
                        placeholder = "https://searx.example/search",
                    ) {
                        searx = it
                        WebSearchSettings.setSearxngUrl(context, it)
                    }
                    WebSearchSettings.Engine.BING,
                    WebSearchSettings.Engine.TAVILY,
                    WebSearchSettings.Engine.BOCHA,
                    WebSearchSettings.Engine.EXA,
                    WebSearchSettings.Engine.BRAVE,
                    WebSearchSettings.Engine.JINA,
                    WebSearchSettings.Engine.ZHIPU,
                    -> CredentialField(
                        label = stringResource(R.string.web_search_api_key),
                        value = keys[current].orEmpty(),
                        placeholder = stringResource(R.string.web_search_api_key_placeholder),
                    ) {
                        keys = keys + (current to it)
                        WebSearchSettings.setApiKey(context, current, it)
                    }
                    WebSearchSettings.Engine.CUSTOM -> {
                        CredentialField(
                            label = stringResource(R.string.web_search_custom_url),
                            value = customUrl,
                            placeholder = "https://example.com/search?q={query}",
                        ) {
                            customUrl = it
                            WebSearchSettings.setCustomUrl(context, it)
                        }
                        CredentialField(
                            label = stringResource(R.string.web_search_custom_key),
                            value = customKey,
                            placeholder = stringResource(R.string.web_search_custom_key_placeholder),
                        ) {
                            customKey = it
                            WebSearchSettings.setCustomKey(context, it)
                        }
                        CredentialField(
                            label = stringResource(R.string.web_search_custom_key_header),
                            value = customHeader,
                            placeholder = "Authorization",
                        ) {
                            customHeader = it
                            WebSearchSettings.setCustomKeyHeader(context, it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun engineLabel(engine: WebSearchSettings.Engine): String = when (engine) {
    WebSearchSettings.Engine.DDG -> stringResource(R.string.web_search_engine_ddg)
    WebSearchSettings.Engine.SEARXNG -> stringResource(R.string.web_search_engine_searxng)
    WebSearchSettings.Engine.BING -> stringResource(R.string.web_search_engine_bing)
    WebSearchSettings.Engine.TAVILY -> stringResource(R.string.web_search_engine_tavily)
    WebSearchSettings.Engine.BOCHA -> stringResource(R.string.web_search_engine_bocha)
    WebSearchSettings.Engine.EXA -> stringResource(R.string.web_search_engine_exa)
    WebSearchSettings.Engine.BRAVE -> stringResource(R.string.web_search_engine_brave)
    WebSearchSettings.Engine.JINA -> stringResource(R.string.web_search_engine_jina)
    WebSearchSettings.Engine.ZHIPU -> stringResource(R.string.web_search_engine_zhipu)
    WebSearchSettings.Engine.CUSTOM -> stringResource(R.string.web_search_engine_custom)
}

@Composable
private fun engineDetailFooter(engine: WebSearchSettings.Engine): String = when (engine) {
    WebSearchSettings.Engine.DDG -> stringResource(R.string.web_search_ddg_detail)
    WebSearchSettings.Engine.SEARXNG -> stringResource(R.string.web_search_searxng_detail)
    WebSearchSettings.Engine.BING -> stringResource(R.string.web_search_bing_detail)
    WebSearchSettings.Engine.CUSTOM -> stringResource(R.string.web_search_custom_detail)
    else -> stringResource(R.string.web_search_keyed_detail, engineLabel(engine))
}

@Composable
private fun EngineNavRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        showChevron = true,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.web_search_in_use),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}
