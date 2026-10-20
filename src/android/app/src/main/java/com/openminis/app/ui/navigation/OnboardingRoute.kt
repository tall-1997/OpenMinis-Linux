package com.openminis.app.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.onboarding.OnboardingScreen

/**
 * [T-android-onboarding-flow] First-run flow wiring, kept out of
 * AppNavigation.kt: that file is at its architecture ceiling, and this is the
 * self-contained "show the flow, record completion, leave the route behind"
 * piece.
 */
internal const val ONBOARDING_PREFS = "onboarding"
internal const val KEY_ONBOARDING_DONE = "completed"

@Composable
internal fun OnboardingRoute(
    providerRepository: ProviderRepository,
    navController: NavHostController,
) {
    val context = LocalContext.current
    OnboardingScreen(
        providerRepository = providerRepository,
        onComplete = {
            context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
            navController.navigate(Routes.SESSION_LIST) {
                popUpTo(Routes.ONBOARDING) { inclusive = true }
            }
        },
    )
}
