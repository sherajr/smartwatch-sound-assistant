package com.peaceantz.stagescope.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.help.WatchShortcutsHelpScreen
import com.peaceantz.stagescope.ui.level.LevelDetailsScreen
import com.peaceantz.stagescope.ui.level.LevelScreen
import com.peaceantz.stagescope.ui.level.LevelViewModel
import com.peaceantz.stagescope.ui.main.CaptureSessionViewModel
import com.peaceantz.stagescope.ui.ring.RingCapturesScreen
import com.peaceantz.stagescope.ui.ring.RingDetailsScreen
import com.peaceantz.stagescope.ui.ring.RingScreen
import com.peaceantz.stagescope.ui.ring.RingViewModel
import com.peaceantz.stagescope.ui.settings.CalibrationScreen
import com.peaceantz.stagescope.ui.settings.CalibrationViewModel
import com.peaceantz.stagescope.ui.spectrum.SnapshotManagerScreen
import com.peaceantz.stagescope.ui.spectrum.SpectrumDetailsScreen
import com.peaceantz.stagescope.ui.spectrum.SpectrumScreen
import com.peaceantz.stagescope.ui.spectrum.SpectrumViewModel

const val ROUTE_MAIN = "main"
const val ROUTE_LEVEL_DETAILS = "levelDetails"
const val ROUTE_SPECTRUM_DETAILS = "spectrumDetails"
const val ROUTE_SPECTRUM_SNAPSHOTS = "spectrumSnapshots"
const val ROUTE_RING_DETAILS = "ringDetails"
const val ROUTE_RING_CAPTURES = "ringCaptures"
const val ROUTE_CALIBRATION = "calibration"
const val ROUTE_WATCH_SHORTCUTS_HELP = "watchShortcutsHelp"
private const val KEY_COMPARE_SNAPSHOT_ID = "compareSnapshotId"

/** Page indices within the LEVEL -> SPECTRUM -> RING pager, fixed order per spec. */
object ModePage {
    const val LEVEL = 0
    const val SPECTRUM = 1
    const val RING = 2
    const val COUNT = 3
}

@Composable
fun StageScopeNavHost(
    container: AppContainer,
    pendingAction: ShortcutRequest? = null,
    onPendingActionConsumed: () -> Unit = {},
) {
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) { backStackEntry ->
            val sessionViewModel: CaptureSessionViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
            ) { CaptureSessionViewModel(container) }
            val session = sessionViewModel.session

            val levelViewModel: LevelViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                LevelViewModel(container, session)
            }
            val spectrumViewModel: SpectrumViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                SpectrumViewModel(container, session)
            }
            val ringViewModel: RingViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                RingViewModel(container, session)
            }

            val compareId by backStackEntry.savedStateHandle
                .getStateFlow<String?>(KEY_COMPARE_SNAPSHOT_ID, null)
                .collectAsStateWithLifecycle()

            AppScaffold {
                val pagerState = rememberPagerState(initialPage = ModePage.LEVEL) { ModePage.COUNT }

                // Applies a Tile/complication tap (page switch, optional Measure-start, optional
                // ring capture selection) to this already-alive session exactly once per delivery
                // -- see ShortcutRequest/MainActivity for how replays are ruled out upstream.
                val requestMeasure = rememberAudioPermissionRequester(onGranted = levelViewModel::start)
                LaunchedEffect(pendingAction?.requestId) {
                    val action = pendingAction ?: return@LaunchedEffect
                    pagerState.scrollToPage(action.page)
                    if (action.startMeasure) requestMeasure()
                    action.ringCaptureId?.let { ringViewModel.selectCapture(it) }
                    onPendingActionConsumed()
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
                                ModePage.LEVEL -> LevelScreen(
                                    viewModel = levelViewModel,
                                    onOpenDetails = { navController.navigate(ROUTE_LEVEL_DETAILS) },
                                )
                                ModePage.SPECTRUM -> SpectrumScreen(
                                    viewModel = spectrumViewModel,
                                    compareSnapshotId = compareId,
                                    onOpenDetails = { navController.navigate(ROUTE_SPECTRUM_DETAILS) },
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

        composable(ROUTE_LEVEL_DETAILS) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val levelViewModel: LevelViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                LevelViewModel(container, sessionViewModel.session)
            }
            LevelDetailsScreen(
                container = container,
                viewModel = levelViewModel,
                onOpenCalibration = { navController.navigate(ROUTE_CALIBRATION) },
                onOpenWatchShortcuts = { navController.navigate(ROUTE_WATCH_SHORTCUTS_HELP) },
            )
        }

        composable(ROUTE_SPECTRUM_DETAILS) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val spectrumViewModel: SpectrumViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                SpectrumViewModel(container, sessionViewModel.session)
            }
            SpectrumDetailsScreen(
                viewModel = spectrumViewModel,
                onOpenSnapshots = { navController.navigate(ROUTE_SPECTRUM_SNAPSHOTS) },
            )
        }

        composable(ROUTE_SPECTRUM_SNAPSHOTS) {
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
            CalibrationScreen(viewModel = calibrationViewModel)
        }

        composable(ROUTE_WATCH_SHORTCUTS_HELP) {
            WatchShortcutsHelpScreen()
        }
    }
}
