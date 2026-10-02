package com.peaceantz.stagescope.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.peaceantz.stagescope.phone.PhoneContainer

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("chats", "Chats", Icons.AutoMirrored.Filled.List),
    Tab("issues", "Issues", Icons.Default.Warning),
    Tab("show", "Show", Icons.Default.DateRange),
    Tab("settings", "Settings", Icons.Default.Settings),
)

/**
 * The phone app: five tabs and a small route stack. The stack is a plain string ("chats|chat/<id>")
 * so it survives rotation and process death without any navigation library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneRoot(container: PhoneContainer, deepLink: DeepLink?, onDeepLinkConsumed: () -> Unit) {
    var stackText by rememberSaveable { mutableStateOf("home") }
    val stack = stackText.split('|')
    val route = stack.last()
    // A link to one action opens its conversation *at that action* (not at the bottom, where the newest message is).
    var chatFocus by remember { mutableStateOf<ChatFocus?>(null) }

    fun push(r: String) { stackText = "$stackText|$r" }
    fun switchTab(r: String) { stackText = r }
    fun pop() { stackText = if (stack.size > 1) stack.dropLast(1).joinToString("|") else "home" }

    // Once the person leaves the conversation the request is spent: opening it again later starts at the newest message as usual.
    LaunchedEffect(route) { if (!route.startsWith("chat/")) chatFocus = null }

    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        val conversationId = link.conversationId ?: link.actionId?.let { container.data.actions.get(it)?.conversationId }
        if (conversationId != null) {
            chatFocus = link.actionId?.let { ChatFocus(it) }
            stackText = "chats|chat/$conversationId"
            // The item is now being shown; only now may the watch be told it was opened on the phone.
            container.continuations.onOpened(link.conversationId, link.actionId)
        }
        onDeepLinkConsumed()
    }

    BackHandler(enabled = stack.size > 1 || route != "home") { pop() }

    val convs by container.data.conversations.state.collectAsState()
    val isTab = TABS.any { it.route == route }
    val title = when {
        route == "home" -> "StageScope"
        route == "chats" -> "Conversations"
        route == "issues" -> "Issues"
        route == "show" -> "Show"
        route == "settings" -> "Settings"
        route.startsWith("chat/") -> convs.conversations[route.removePrefix("chat/")]?.title ?: "New conversation"
        route.startsWith("issue/") -> "Issue"
        route == "settings/providers" -> "AI providers"
        route == "settings/google" -> "Gmail & Calendar"
        route == "settings/speech" -> "Voice & speech"
        route == "settings/usage" -> "Usage & limits"
        route == "settings/dev" -> "Developer"
        else -> "StageScope"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (!isTab) IconButton(onClick = ::pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (isTab) NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = { switchTab(t.route) }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).imePadding()) {
            when {
                route == "home" -> HomeScreen(container, nav = ::push, switchTab = ::switchTab)
                route == "chats" -> ChatsScreen(container, openChat = { push("chat/$it") })
                route.startsWith("chat/") -> ChatDetailScreen(
                    container, route.removePrefix("chat/"), focus = chatFocus,
                    openGoogleSetup = { push("settings/google") }, openProviders = { push("settings/providers") },
                )
                route == "issues" -> IssuesScreen(container, openIssue = { push("issue/$it") })
                route.startsWith("issue/") -> IssueDetailScreen(container, route.removePrefix("issue/"), onClose = ::pop)
                route == "show" -> ShowScreen(container)
                route == "settings" -> SettingsHomeScreen(nav = ::push)
                route == "settings/providers" -> ProvidersScreen(container)
                route == "settings/google" -> GoogleScreen(container)
                route == "settings/speech" -> SpeechScreen(container)
                route == "settings/usage" -> UsageScreen(container)
                route == "settings/dev" -> DevScreen(container)
            }
        }
    }
}
