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
import com.peaceantz.stagescope.ui.components.RotatedContent
import com.peaceantz.stagescope.ui.components.rememberAudioPermissionRequester
import com.peaceantz.stagescope.ui.help.WatchShortcutsHelpScreen
import com.peaceantz.stagescope.ui.main.CaptureSessionViewModel
import com.peaceantz.stagescope.ui.ring.RingDetailsScreen
import com.peaceantz.stagescope.ui.ring.RingScreen
import com.peaceantz.stagescope.ui.ring.RingViewModel
import com.peaceantz.stagescope.ui.rotation.OrientationViewModel
import com.peaceantz.stagescope.ui.settings.AppearanceScreen
import com.peaceantz.stagescope.ui.settings.CalibrationScreen
import com.peaceantz.stagescope.ui.settings.CalibrationViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.peaceantz.stagescope.shared.assistant.TaskKind
import com.peaceantz.stagescope.shared.measurement.SnapshotOrigin
import com.peaceantz.stagescope.ui.assistant.ActionReviewScreen
import com.peaceantz.stagescope.ui.assistant.AssistantNavigator
import com.peaceantz.stagescope.ui.assistant.AssistantRoutes
import com.peaceantz.stagescope.ui.assistant.AssistantScreen
import com.peaceantz.stagescope.ui.assistant.AssistantSettingsScreen
import com.peaceantz.stagescope.ui.assistant.AssistantViewModel
import com.peaceantz.stagescope.ui.assistant.IssueDetailScreen
import com.peaceantz.stagescope.ui.assistant.IssuesScreen
import com.peaceantz.stagescope.ui.assistant.ListenScreen
import com.peaceantz.stagescope.ui.assistant.MemosScreen
import com.peaceantz.stagescope.ui.assistant.ProviderPickerScreen
import com.peaceantz.stagescope.ui.assistant.ReplyScreen
import com.peaceantz.stagescope.ui.assistant.ShowPickerScreen
import com.peaceantz.stagescope.ui.assistant.TasksScreen

const val ROUTE_MAIN = "main"
const val ROUTE_ANALYZER_DETAILS = "analyzerDetails"
const val ROUTE_SNAPSHOTS = "snapshots"
const val ROUTE_RING_DETAILS = "ringDetails"
const val ROUTE_CALIBRATION = "calibration"
const val ROUTE_APPEARANCE = "appearance"
const val ROUTE_WATCH_SHORTCUTS_HELP = "watchShortcutsHelp"
private const val KEY_COMPARE_SNAPSHOT_ID = "compareSnapshotId"

/** Page indices within the ANALYZER -> RING -> ASSISTANT pager, fixed order per spec. LEVEL and SPECTRUM were
 *  combined into ANALYZER; [ShortcutRequest.fromIntent] still routes their old shortcut strings
 *  here so existing Tile/complication PendingIntents keep working unmodified. ASSISTANT is the optional,
 *  phone-backed third page: the two instrument pages never depend on it. */
object ModePage {
    const val ANALYZER = 0
    const val RING = 1
    const val ASSISTANT = 2
    const val COUNT = 3
}

@Composable
fun StageScopeNavHost(
    container: AppContainer,
    pendingAction: ShortcutRequest? = null,
    onPendingActionConsumed: () -> Unit = {},
) {
    val navController = rememberSwipeDismissableNavController()

    // How the Assistant page (and its sub-screens) move around. Created once per controller.
    val assistantNav = remember(navController) {
        AssistantNavigator(
            ask = { task, origin, conversationId, editsActionId ->
                navController.navigate(AssistantRoutes.listen(task, origin, conv = conversationId, edit = editsActionId))
            },
            reply = { navController.navigate(AssistantRoutes.reply(it)) },
            action = { conversationId, actionId -> navController.navigate(AssistantRoutes.action(conversationId, actionId)) },
            tasks = { navController.navigate(AssistantRoutes.TASKS) },
            issues = { navController.navigate(AssistantRoutes.ISSUES) },
            issue = { navController.navigate(AssistantRoutes.issue(it)) },
            settings = { navController.navigate(AssistantRoutes.SETTINGS) },
            providers = { navController.navigate(AssistantRoutes.PROVIDERS) },
            shows = { navController.navigate(AssistantRoutes.SHOWS) },
            memos = { navController.navigate(AssistantRoutes.MEMOS) },
            reviewMemo = { navController.navigate(AssistantRoutes.listen(TaskKind.FREE_CHAT, SnapshotOrigin.ASSISTANT, memo = it)) },
            resumeDraft = { navController.navigate(AssistantRoutes.listen(TaskKind.FREE_CHAT, SnapshotOrigin.ASSISTANT, draft = true)) },
            close = { navController.popBackStack() },
        )
    }

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
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                OrientationViewModel(container)
            }
            val assistantViewModel: AssistantViewModel = viewModel(viewModelStoreOwner = backStackEntry) {
                AssistantViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            val orientationLocked by orientationViewModel.locked.collectAsStateWithLifecycle()

            val compareId by backStackEntry.savedStateHandle
                .getStateFlow<String?>(KEY_COMPARE_SNAPSHOT_ID, null)
                .collectAsStateWithLifecycle()

            AppScaffold {
                // A cold launch from a Tile/complication/notification starts the pager ON the requested page
                // instead of composing page 0 and jumping. Confirmed on a Wear OS 6 emulator: a jump made in
                // the first frames loses a race with Wear's hierarchical focus -- the page *before* the target
                // is composed right after it, takes focus, and its focusable() animates the pager back to it,
                // so "Ask AI" landed on Ring and "Ring" on Analyzer. (rememberPagerState only reads initialPage
                // when it first creates the state, so a page restored after rotation/process death still wins.)
                val pagerState = rememberPagerState(initialPage = currentPendingAction.value?.page ?: ModePage.ANALYZER) { ModePage.COUNT }

                // Applies a Tile/complication tap (page switch, optional Measure-start, optional
                // ring capture selection) to this already-alive session exactly once per delivery
                // -- see ShortcutRequest/MainActivity for how replays are ruled out upstream.
                val requestMeasure = rememberAudioPermissionRequester(onGranted = analyzerViewModel::start)
                LaunchedEffect(currentPendingAction.value?.requestId) {
                    val action = currentPendingAction.value ?: return@LaunchedEffect
                    if (pagerState.currentPage != action.page) pagerState.scrollToPage(action.page)
                    if (action.startMeasure) requestMeasure()
                    action.ringCaptureId?.let { ringViewModel.selectCapture(it) }
                    currentOnPendingActionConsumed.value()
                }

                HorizontalPagerScaffold(pagerState = pagerState) {
                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
                        // Crown now drives the shared instrument rotation on each page instead of
                        // paging -- see AnalyzerScreen/RingScreen's own onRotaryScrollEvent wiring.
                        rotaryScrollableBehavior = null,
                    ) { page ->
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            when (page) {
                                ModePage.ANALYZER -> AnalyzerScreen(
                                    viewModel = analyzerViewModel,
                                    compareSnapshotId = compareId,
                                    angleDegrees = angleDegrees,
                                    orientationLocked = orientationLocked,
                                    onRotaryDelta = orientationViewModel::onRotaryDelta,
                                    onOpenDetails = { navController.navigate(ROUTE_ANALYZER_DETAILS) },
                                )
                                ModePage.RING -> RingScreen(
                                    viewModel = ringViewModel,
                                    angleDegrees = angleDegrees,
                                    orientationLocked = orientationLocked,
                                    onRotaryDelta = orientationViewModel::onRotaryDelta,
                                    onOpenDetails = { navController.navigate(ROUTE_RING_DETAILS) },
                                )
                                // The crown scrolls this page (it is a list); it still shows at the shared display angle.
                                ModePage.ASSISTANT -> AssistantScreen(
                                    vm = assistantViewModel,
                                    angleDegrees = angleDegrees,
                                    nav = assistantNav,
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
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                OrientationViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            RotatedContent(angleDegrees) {
                AnalyzerDetailsScreen(
                    container = container,
                    viewModel = analyzerViewModel,
                    orientationViewModel = orientationViewModel,
                    onOpenCalibration = { navController.navigate(ROUTE_CALIBRATION) },
                    onOpenSnapshots = { navController.navigate(ROUTE_SNAPSHOTS) },
                    onOpenAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                    onOpenWatchShortcuts = { navController.navigate(ROUTE_WATCH_SHORTCUTS_HELP) },
                    onAskAi = { assistantNav.ask(TaskKind.ANALYZE_SOUND, SnapshotOrigin.ANALYZER, null, null) },
                )
            }
        }

        composable(ROUTE_SNAPSHOTS) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                OrientationViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            RotatedContent(angleDegrees) {
                SnapshotManagerScreen(
                    container = container,
                    onSelectCompare = { id ->
                        navController.getBackStackEntry(ROUTE_MAIN).savedStateHandle[KEY_COMPARE_SNAPSHOT_ID] = id
                        navController.popBackStack()
                    },
                )
            }
        }

        composable(ROUTE_RING_DETAILS) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val ringViewModel: RingViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                RingViewModel(container, sessionViewModel.session)
            }
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                OrientationViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            RotatedContent(angleDegrees) {
                RingDetailsScreen(
                    viewModel = ringViewModel,
                    orientationViewModel = orientationViewModel,
                    onAskAi = { assistantNav.ask(TaskKind.ANALYZE_SOUND, SnapshotOrigin.RING, null, null) },
                )
            }
        }

        composable(ROUTE_CALIBRATION) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val sessionViewModel: CaptureSessionViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CaptureSessionViewModel(container)
            }
            val calibrationViewModel: CalibrationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                CalibrationViewModel(container, sessionViewModel.session)
            }
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                OrientationViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            RotatedContent(angleDegrees) {
                CalibrationScreen(viewModel = calibrationViewModel, onExit = { navController.popBackStack() })
            }
        }

        composable(ROUTE_APPEARANCE) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                OrientationViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            RotatedContent(angleDegrees) {
                AppearanceScreen(container = container)
            }
        }

        composable(ROUTE_WATCH_SHORTCUTS_HELP) { entry ->
            val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
            val orientationViewModel: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) {
                OrientationViewModel(container)
            }
            val angleDegrees by orientationViewModel.angleDegrees.collectAsStateWithLifecycle()
            RotatedContent(angleDegrees) {
                WatchShortcutsHelpScreen()
            }
        }

        // ---- Assistant sub-screens. Optional and phone-backed; they share the AssistantViewModel scoped to "main". ----

        composable(
            route = AssistantRoutes.LISTEN,
            arguments = listOf(
                navArgument(AssistantRoutes.ARG_TASK) { type = NavType.StringType },
                navArgument(AssistantRoutes.ARG_ORIGIN) { type = NavType.StringType },
                navArgument(AssistantRoutes.ARG_MEMO) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(AssistantRoutes.ARG_CONV) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(AssistantRoutes.ARG_EDIT) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(AssistantRoutes.ARG_DRAFT) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            val args = entry.arguments
            ListenScreen(
                vm = vm,
                task = runCatching { TaskKind.valueOf(args?.getString(AssistantRoutes.ARG_TASK).orEmpty()) }.getOrDefault(TaskKind.FREE_CHAT),
                origin = runCatching { SnapshotOrigin.valueOf(args?.getString(AssistantRoutes.ARG_ORIGIN).orEmpty()) }.getOrDefault(SnapshotOrigin.ASSISTANT),
                memoId = args?.getString(AssistantRoutes.ARG_MEMO),
                conversationId = args?.getString(AssistantRoutes.ARG_CONV),
                editsActionId = args?.getString(AssistantRoutes.ARG_EDIT),
                resumeDraft = args?.getString(AssistantRoutes.ARG_DRAFT) == "1",
                angleDegrees = angle,
                onClose = { navController.popBackStack() },
            )
        }

        composable(
            route = AssistantRoutes.REPLY,
            arguments = listOf(navArgument(AssistantRoutes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            ReplyScreen(vm, entry.arguments?.getString(AssistantRoutes.ARG_CONVERSATION_ID).orEmpty(), angle, assistantNav)
        }

        composable(
            route = AssistantRoutes.ACTION,
            arguments = listOf(
                navArgument(AssistantRoutes.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(AssistantRoutes.ARG_ACTION_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            ActionReviewScreen(
                vm, entry.arguments?.getString(AssistantRoutes.ARG_CONVERSATION_ID).orEmpty(),
                entry.arguments?.getString(AssistantRoutes.ARG_ACTION_ID).orEmpty(), angle, assistantNav,
            )
        }

        composable(AssistantRoutes.TASKS) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            TasksScreen(vm, angle, assistantNav)
        }

        composable(AssistantRoutes.ISSUES) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            IssuesScreen(vm, angle, assistantNav)
        }

        composable(
            route = AssistantRoutes.ISSUE,
            arguments = listOf(navArgument(AssistantRoutes.ARG_ISSUE_ID) { type = NavType.StringType }),
        ) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            IssueDetailScreen(vm, entry.arguments?.getString(AssistantRoutes.ARG_ISSUE_ID).orEmpty(), angle, onClose = { navController.popBackStack() })
        }

        composable(AssistantRoutes.SETTINGS) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            AssistantSettingsScreen(vm, angle, assistantNav)
        }

        composable(AssistantRoutes.PROVIDERS) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            ProviderPickerScreen(vm, angle)
        }

        composable(AssistantRoutes.SHOWS) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            ShowPickerScreen(vm, angle, onClose = { navController.popBackStack() })
        }

        composable(AssistantRoutes.MEMOS) { entry ->
            val (vm, angle) = rememberAssistantScope(container, navController, entry)
            MemosScreen(vm, angle, assistantNav)
        }
    }
}

/** The shared Assistant ViewModel and display angle, fetched from the "main" entry exactly like every other route does. */
@Composable
private fun rememberAssistantScope(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
): Pair<AssistantViewModel, Float> {
    val mainEntry = remember(entry) { navController.getBackStackEntry(ROUTE_MAIN) }
    val vm: AssistantViewModel = viewModel(viewModelStoreOwner = mainEntry) { AssistantViewModel(container) }
    val orientation: OrientationViewModel = viewModel(viewModelStoreOwner = mainEntry) { OrientationViewModel(container) }
    val angle by orientation.angleDegrees.collectAsStateWithLifecycle()
    return vm to angle
}
