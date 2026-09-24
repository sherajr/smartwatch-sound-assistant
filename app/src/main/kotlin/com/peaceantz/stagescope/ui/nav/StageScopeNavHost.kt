package com.peaceantz.stagescope.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.PagerScaffoldDefaults
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.ui.analyzer.AnalyzerDetailsScreen
import com.peaceantz.stagescope.ui.analyzer.AnalyzerScreen
import com.peaceantz.stagescope.ui.analyzer.AnalyzerViewModel
import com.peaceantz.stagescope.ui.analyzer.SnapshotManagerScreen
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.help.WatchShortcutsHelpScreen
import com.peaceantz.stagescope.ui.main.CaptureSessionViewModel
import com.peaceantz.stagescope.ui.ring.RingCapturesScreen
import com.peaceantz.stagescope.ui.ring.RingDetailsScreen
import com.peaceantz.stagescope.ui.ring.RingScreen
import com.peaceantz.stagescope.ui.ring.RingViewModel
import com.peaceantz.stagescope.ui.settings.AppearanceScreen
import com.peaceantz.stagescope.ui.settings.CalibrationScreen
import com.peaceantz.stagescope.ui.settings.CalibrationViewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

const val ROUTE_MAIN = "main"
const val ROUTE_ANALYZER_DETAILS = "analyzerDetails"
const val ROUTE_SNAPSHOTS = "snapshots"
const val ROUTE_RING_DETAILS = "ringDetails"
const val ROUTE_RING_CAPTURES = "ringCaptures"
const val ROUTE_CALIBRATION = "calibration"
const val ROUTE_APPEARANCE = "appearance"
const val ROUTE_WATCH_SHORTCUTS_HELP = "watchShortcutsHelp"
private const val KEY_COMPARE_SNAPSHOT_ID = "compareSnapshotId"

/** Page indices within the ANALYZER -> RING pager, fixed order per spec. LEVEL and SPECTRUM were
 *  combined into ANALYZER; [ShortcutRequest.fromIntent] still routes their old shortcut strings
 *  here so existing Tile/complication PendingIntents keep working unmodified. */
object ModePage {
    const val ANALYZER = 0
    const val RING = 1
    const val COUNT = 2
}

@Composable
fun StageScopeNavHost(
    container: AppContainer,
    pendingAction: ShortcutRequest? = null,
    onPendingActionConsumed: () -> Unit = {},
) {
    val navController = rememberSwipeDismissableNavController()

    // Wear Navigation composes ROUTE_MAIN's content lambda in its own composition scoped to the
    // backstack entry, which is NOT re-invoked just because this outer function recomposes with a
    // new `pendingAction` (a second Tile/complication tap while the app is already open) -- a
    // closure over the raw parameter would silently keep seeing the value from the FIRST
    // composition. rememberUpdatedState gives the inner LaunchedEffect a stable reference whose
    // `.value` always reflects the latest tap, without restarting the effect on every recomposition.
    val currentPendingAction = rememberUpdatedState(pendingAction)
    val currentOnPendingActionConsumed = rememberUpdatedState(onPendingActionConsumed)

    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) { backStackEntry ->
            val sessionViewModel: CaptureSessionViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
            ) { CaptureSessionViewModel(container) }
            val session = sessionViewModel.session

            val analyzerViewModel: AnalyzerViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                AnalyzerViewModel(container, session)
            }
            val ringViewModel: RingViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                RingViewModel(container, session)
            }

            val compareId by backStackEntry.savedStateHandle
                .getStateFlow<String?>(KEY_COMPARE_SNAPSHOT_ID, null)
                .collectAsStateWithLifecycle()

            AppScaffold {
                val pagerState = rememberPagerState(initialPage = ModePage.ANALYZER) { ModePage.COUNT }

                // Applies a Tile/complication tap (page switch, optional Measure-start, optional
                // ring capture selection) to this already-alive session exactly once per delivery
                // -- see ShortcutRequest/MainActivity for how replays are ruled out upstream.
                val requestMeasure = rememberAudioPermissionRequester(onGranted = analyzerViewModel::start)
                LaunchedEffect(currentPendingAction.value?.requestId) {
                    val action = currentPendingAction.value ?: return@LaunchedEffect
                    pagerState.scrollToPage(action.page)
                    if (action.startMeasure) requestMeasure()
                    action.ringCaptureId?.let { ringViewModel.selectCapture(it) }
                    currentOnPendingActionConsumed.value()
                }

                HorizontalPagerScaffold(pagerState = pagerState) {
                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
                        // Crown is repurposed per-page (spectrum cursor / ring history); never page via rotary.
                        rotaryScrollableBehavior = null,
                    ) { page ->
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            when (page) {
                                ModePage.ANALYZER -> AnalyzerScreen(
                                    viewModel = analyzerViewModel,
                                    compareSnapshotId = compareId,
                                    onOpenDetails = { navController.navigate(ROUTE_ANALYZER_DETAILS) },
                                )
                                ModePage.RING -> RingScreen(
                                    viewModel = ringViewModel,
                                    onOpenDetails = { navController.navigate(ROUTE_RING_DETAILS) },
                                    onOpenCaptures = { navController.navigate(ROUTE_RING_CAPTURES) },
                                )
                            }
                        }
                    }
                }
            }
        }

        composable(ROUTE_ANALYZER_DETAILS) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val analyzerViewModel: AnalyzerViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                AnalyzerViewModel(container, sessionViewModel.session)
            }
            AnalyzerDetailsScreen(
                container = container,
                viewModel = analyzerViewModel,
                onOpenCalibration = { navController.navigate(ROUTE_CALIBRATION) },
                onOpenSnapshots = { navController.navigate(ROUTE_SNAPSHOTS) },
                onOpenAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                onOpenWatchShortcuts = { navController.navigate(ROUTE_WATCH_SHORTCUTS_HELP) },
            )
        }

        composable(ROUTE_SNAPSHOTS) {
            SnapshotManagerScreen(
                container = container,
                onSelectCompare = { id ->
                    navController.getBackStackEntry(ROUTE_MAIN).savedStateHandle[KEY_COMPARE_SNAPSHOT_ID] = id
                    navController.popBackStack()
                },
            )
        }

        composable(ROUTE_RING_DETAILS) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val ringViewModel: RingViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                RingViewModel(container, sessionViewModel.session)
            }
            RingDetailsScreen(viewModel = ringViewModel)
        }

        composable(ROUTE_RING_CAPTURES) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val ringViewModel: RingViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                RingViewModel(container, sessionViewModel.session)
            }
            RingCapturesScreen(viewModel = ringViewModel)
        }

        composable(ROUTE_CALIBRATION) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val calibrationViewModel: CalibrationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CalibrationViewModel(container, sessionViewModel.session)
            }
            CalibrationScreen(viewModel = calibrationViewModel, onExit = { navController.popBackStack() })
        }

        composable(ROUTE_APPEARANCE) {
            AppearanceScreen(container = container)
        }

        composable(ROUTE_WATCH_SHORTCUTS_HELP) {
            WatchShortcutsHelpScreen()
        }
    }
}
