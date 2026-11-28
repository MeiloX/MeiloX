package com.ljyh.mei.ui.screen.about

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.constants.BetaUpdatesEnabledKey
import com.ljyh.mei.source.update.SourcePluginUpdate
import com.ljyh.mei.source.update.SourceUpdateManager
import com.ljyh.mei.utils.VersionUpdateChecker
import com.ljyh.mei.utils.VersionUpdateResult
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

internal sealed interface StartupUpdate {
    data class App(val release: VersionUpdateResult.UpdateAvailable) : StartupUpdate
    data class Plugins(val updates: List<SourcePluginUpdate>) : StartupUpdate
}

@HiltViewModel
internal class StartupUpdateViewModel @Inject constructor(
    @ApplicationContext context: Context,
    sourceUpdates: SourceUpdateManager,
) : ViewModel() {
    private val pendingUpdate = MutableStateFlow<StartupUpdate?>(null)
    val pending = pendingUpdate.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                pendingUpdate.value = checkStartupUpdates(
                    betaEnabled = { context.dataStore.data.first()[BetaUpdatesEnabledKey] ?: false },
                    checkApp = { VersionUpdateChecker.check(BuildConfig.VERSION_NAME, it) },
                    checkPlugins = sourceUpdates::checkOnStartup,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Startup update check failed")
            }
        }
    }

    fun dismiss() {
        pendingUpdate.value = null
    }
}

internal suspend fun checkStartupUpdates(
    betaEnabled: suspend () -> Boolean,
    checkApp: suspend (Boolean) -> VersionUpdateResult,
    checkPlugins: suspend () -> List<SourcePluginUpdate>,
): StartupUpdate? {
    val beta = betaEnabled()
    val release = checkApp(beta)
    if (betaEnabled() != beta) return null
    return when (release) {
        is VersionUpdateResult.UpdateAvailable -> release.takeIf { it.comparisonKnown }?.let(StartupUpdate::App)
        VersionUpdateResult.Failed -> null
        VersionUpdateResult.UpToDate -> {
            val updates = checkPlugins()
            if (updates.isEmpty() || betaEnabled() != beta) null else StartupUpdate.Plugins(updates)
        }
    }
}
