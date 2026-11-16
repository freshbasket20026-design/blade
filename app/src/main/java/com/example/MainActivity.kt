package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.i18n.BengaliBladeStrings
import com.example.core.i18n.EnglishBladeStrings
import com.example.core.i18n.LocalBladeStrings
import com.example.core.i18n.bladeStrings
import com.example.data.model.VideoItem
import com.example.ui.screens.AdminScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.CreateScreen
import com.example.ui.screens.DiscoverScreen
import com.example.ui.screens.EditProfileScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InboxScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.VideoDetailPlayerScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.BladeObsidian
import com.example.ui.theme.BladeRed
import com.example.ui.theme.BladeRedAccent
import com.example.ui.theme.BladeRedDark
import com.example.ui.theme.BladeTheme
import com.example.ui.theme.TextWhite
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.BladeViewModelFactory
import com.example.ui.viewmodel.CreateViewModel
import com.example.ui.viewmodel.DiscoverViewModel
import com.example.ui.viewmodel.FeedTab
import com.example.ui.viewmodel.FeedViewModel
import com.example.ui.viewmodel.InboxViewModel
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ProfileViewModel
import kotlinx.serialization.Serializable

@Serializable object SplashRoute
@Serializable object HomeRoute
@Serializable object DiscoverRoute
@Serializable object CreateRoute
@Serializable object InboxRoute
@Serializable object ProfileRoute
@Serializable object AuthRoute
@Serializable object EditProfileRoute
@Serializable object SettingsRoute
@Serializable object WalletRoute
@Serializable object AdminRoute
@Serializable data class UserProfileRoute(val userId: String)
@Serializable data class ChatRoute(val conversationId: String)
@Serializable data class VideoPlayerRoute(
    val videoUrl: String,
    val title: String,
    val caption: String,
    val username: String,
    val filterName: String,
    val trimStartMs: Long,
    val trimEndMs: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val isDarkMode by mainViewModel.isDarkMode.collectAsStateWithLifecycle()
            val languageCode by mainViewModel.languageCode.collectAsStateWithLifecycle()

            val activeStrings = if (languageCode == "bn") BengaliBladeStrings else EnglishBladeStrings

            CompositionLocalProvider(LocalBladeStrings provides activeStrings) {
                BladeTheme(darkTheme = isDarkMode) {
                    BladeRootApp(mainViewModel = mainViewModel)
                }
            }
        }
    }
}

@Composable
private fun BladeRootApp(mainViewModel: MainViewModel) {
    val strings = bladeStrings()
    val factory = remember(mainViewModel.repository) { BladeViewModelFactory(mainViewModel.repository) }

    val feedViewModel: FeedViewModel = viewModel(factory = factory)
    val discoverViewModel: DiscoverViewModel = viewModel(factory = factory)
    val createViewModel: CreateViewModel = viewModel(factory = factory)
    val inboxViewModel: InboxViewModel = viewModel(factory = factory)
    val profileViewModel: ProfileViewModel = viewModel(factory = factory)
    val authViewModel: AuthViewModel = viewModel(factory = factory)

    val currentUser by mainViewModel.currentUser.collectAsStateWithLifecycle()
    val inboxUiState by inboxViewModel.uiState.collectAsStateWithLifecycle()
    val isDarkMode by mainViewModel.isDarkMode.collectAsStateWithLifecycle()
    val languageCode by mainViewModel.languageCode.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        mainViewModel.toastMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(currentUser?.id) {
        if (currentUser == null) {
            inboxViewModel.clearUnreadCount()
        } else {
            while (isActive) {
                inboxViewModel.refreshUnreadCount()
                delay(20_000)
            }
        }
    }

    val showTopLevelNav = currentDest?.let { dest ->
        dest.hasRoute<HomeRoute>() ||
            dest.hasRoute<DiscoverRoute>() ||
            dest.hasRoute<InboxRoute>() ||
            dest.hasRoute<ProfileRoute>()
    } ?: false

    val isOnHome = currentDest?.hasRoute<HomeRoute>() == true

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openVideoDetail(video: VideoItem) {
        navController.navigate(
            VideoPlayerRoute(
                videoUrl = video.videoUrl,
                title = video.title,
                caption = video.caption,
                username = video.creator?.username ?: "creator",
                filterName = video.filterName,
                trimStartMs = video.trimStartMs,
                trimEndMs = video.trimEndMs
            )
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showTopLevelNav && !useRail) {
                    NavigationBar(
                        containerColor = if (isOnHome) BladeObsidian else MaterialTheme.colorScheme.surface,
                        contentColor = if (isOnHome) TextWhite else MaterialTheme.colorScheme.onSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        val itemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BladeRed,
                            selectedTextColor = BladeRed,
                            unselectedIconColor = if (isOnHome) TextWhite.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = if (isOnHome) TextWhite.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = BladeRed.copy(alpha = 0.16f)
                        )

                        NavigationBarItem(
                            selected = currentDest?.hasRoute<HomeRoute>() == true,
                            onClick = {
                                feedViewModel.selectTab(FeedTab.FOR_YOU)
                                navigateTopLevel(HomeRoute)
                            },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute<HomeRoute>() == true) Icons.Rounded.Home else Icons.Outlined.Home,
                                    contentDescription = strings.navHome
                                )
                            },
                            label = { Text(strings.navHome, maxLines = 1) },
                            colors = itemColors,
                            modifier = Modifier.testTag("nav_home")
                        )

                        NavigationBarItem(
                            selected = currentDest?.hasRoute<DiscoverRoute>() == true,
                            onClick = { navigateTopLevel(DiscoverRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute<DiscoverRoute>() == true) Icons.Rounded.LocalFireDepartment else Icons.Outlined.LocalFireDepartment,
                                    contentDescription = strings.navDiscover
                                )
                            },
                            label = { Text(strings.navDiscover, maxLines = 1) },
                            colors = itemColors,
                            modifier = Modifier.testTag("nav_discover")
                        )

                        NavigationBarItem(
                            selected = false,
                            onClick = { navController.navigate(CreateRoute) },
                            icon = { BladeCreateNavBadge() },
                            label = { Text(strings.navCreate, maxLines = 1) },
                            colors = itemColors,
                            modifier = Modifier.testTag("nav_create")
                        )

                        NavigationBarItem(
                            selected = currentDest?.hasRoute<InboxRoute>() == true,
                            onClick = {
                                if (currentUser != null) inboxViewModel.loadInbox()
                                navigateTopLevel(InboxRoute)
                            },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (inboxUiState.unreadCount > 0) {
                                            Badge(
                                                containerColor = BladeRed,
                                                contentColor = TextWhite
                                            ) {
                                                Text(
                                                    text = if (inboxUiState.unreadCount > 99) "99+" else inboxUiState.unreadCount.toString()
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (currentDest?.hasRoute<InboxRoute>() == true) Icons.Rounded.Forum else Icons.Outlined.Forum,
                                        contentDescription = strings.navInbox
                                    )
                                }
                            },
                            label = { Text(strings.navInbox, maxLines = 1) },
                            colors = itemColors,
                            modifier = Modifier.testTag("nav_inbox")
                        )

                        NavigationBarItem(
                            selected = currentDest?.hasRoute<ProfileRoute>() == true,
                            onClick = {
                                currentUser?.id?.let { profileViewModel.loadProfile(it) }
                                navigateTopLevel(ProfileRoute)
                            },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute<ProfileRoute>() == true) Icons.Rounded.AccountCircle else Icons.Outlined.AccountCircle,
                                    contentDescription = strings.navProfile
                                )
                            },
                            label = { Text(strings.navProfile, maxLines = 1) },
                            colors = itemColors,
                            modifier = Modifier.testTag("nav_profile")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showTopLevelNav && useRail) {
                    val railColors = NavigationRailItemDefaults.colors(
                        selectedIconColor = BladeRed,
                        selectedTextColor = BladeRed,
                        unselectedIconColor = if (isOnHome) TextWhite.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = if (isOnHome) TextWhite.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = BladeRed.copy(alpha = 0.16f)
                    )
                    NavigationRail(
                        containerColor = if (isOnHome) BladeObsidian else MaterialTheme.colorScheme.surface
                    ) {
                        NavigationRailItem(
                            selected = currentDest?.hasRoute<HomeRoute>() == true,
                            onClick = {
                                feedViewModel.selectTab(FeedTab.FOR_YOU)
                                navigateTopLevel(HomeRoute)
                            },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = strings.navHome) },
                            label = { Text(strings.navHome) },
                            colors = railColors
                        )
                        NavigationRailItem(
                            selected = currentDest?.hasRoute<DiscoverRoute>() == true,
                            onClick = { navigateTopLevel(DiscoverRoute) },
                            icon = { Icon(Icons.Rounded.LocalFireDepartment, contentDescription = strings.navDiscover) },
                            label = { Text(strings.navDiscover) },
                            colors = railColors
                        )
                        NavigationRailItem(
                            selected = false,
                            onClick = { navController.navigate(CreateRoute) },
                            icon = { BladeCreateNavBadge() },
                            label = { Text(strings.navCreate) },
                            colors = railColors
                        )
                        NavigationRailItem(
                            selected = currentDest?.hasRoute<InboxRoute>() == true,
                            onClick = {
                                if (currentUser != null) inboxViewModel.loadInbox()
                                navigateTopLevel(InboxRoute)
                            },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (inboxUiState.unreadCount > 0) {
                                            Badge(
                                                containerColor = BladeRed,
                                                contentColor = TextWhite
                                            ) {
                                                Text(
                                                    text = if (inboxUiState.unreadCount > 99) "99+" else inboxUiState.unreadCount.toString()
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Forum, contentDescription = strings.navInbox)
                                }
                            },
                            label = { Text(strings.navInbox) },
                            colors = railColors
                        )
                        NavigationRailItem(
                            selected = currentDest?.hasRoute<ProfileRoute>() == true,
                            onClick = {
                                currentUser?.id?.let { profileViewModel.loadProfile(it) }
                                navigateTopLevel(ProfileRoute)
                            },
                            icon = { Icon(Icons.Rounded.AccountCircle, contentDescription = strings.navProfile) },
                            label = { Text(strings.navProfile) },
                            colors = railColors
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = SplashRoute,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<SplashRoute> {
                        SplashScreen(
                            onFinished = {
                                navController.navigate(HomeRoute) {
                                    popUpTo<SplashRoute> { inclusive = true }
                                }
                            }
                        )
                    }
                    composable<HomeRoute> {
                        HomeScreen(
                            feedViewModel = feedViewModel,
                            currentUser = currentUser,
                            languageCode = languageCode,
                            isSupabaseConfigured = mainViewModel.isSupabaseConfigured,
                            onToggleLanguage = { mainViewModel.setLanguage(it) },
                            onNavigateToSearch = { navigateTopLevel(DiscoverRoute) },
                            onNavigateToHashtag = { tag ->
                                discoverViewModel.filterByHashtag(tag)
                                navigateTopLevel(DiscoverRoute)
                            },
                            onNavigateToSound = { soundId ->
                                discoverViewModel.filterBySound(soundId)
                                navigateTopLevel(DiscoverRoute)
                            },
                            onNavigateToCreator = { uid ->
                                navController.navigate(UserProfileRoute(uid))
                            },
                            onNavigateToCreate = { navController.navigate(CreateRoute) },
                            onNavigateToAuth = { navController.navigate(AuthRoute) },
                            onShowMessage = { mainViewModel.emitMessage(it) }
                        )
                    }

                    composable<DiscoverRoute> {
                        DiscoverScreen(
                            discoverViewModel = discoverViewModel,
                            onVideoClick = { openVideoDetail(it) },
                            onCreatorClick = { uid -> navController.navigate(UserProfileRoute(uid)) },
                            onUseSound = { sound ->
                                createViewModel.preselectSound(sound)
                                navController.navigate(CreateRoute)
                            }
                        )
                    }

                    composable<CreateRoute> {
                        CreateScreen(
                            createViewModel = createViewModel,
                            currentUser = currentUser,
                            onClose = { navController.popBackStack() },
                            onRequireAuth = { navController.navigate(AuthRoute) },
                            onUploadSuccess = {
                                mainViewModel.emitMessage("Blade published!")
                                feedViewModel.loadFeed()
                                currentUser?.id?.let { profileViewModel.loadProfile(it) }
                                navController.popBackStack()
                                navigateTopLevel(HomeRoute)
                            }
                        )
                    }

                    composable<InboxRoute> {
                        InboxScreen(
                            inboxViewModel = inboxViewModel,
                            currentUser = currentUser,
                            onNavigateToAuth = { navController.navigate(AuthRoute) },
                            onOpenChat = { convId -> navController.navigate(ChatRoute(convId)) },
                            onOpenCreator = { uid -> navController.navigate(UserProfileRoute(uid)) }
                        )
                    }

                    composable<ProfileRoute> {
                        ProfileScreen(
                            targetUserId = null,
                            currentUser = currentUser,
                            profileViewModel = profileViewModel,
                            isOtherUserScreen = false,
                            onNavigateToAuth = { navController.navigate(AuthRoute) },
                            onNavigateToEditProfile = { navController.navigate(EditProfileRoute) },
                            onNavigateToSettings = { navController.navigate(SettingsRoute) },
                            onNavigateToAdmin = { navController.navigate(AdminRoute) },
                            onProfileUpdated = { updated -> mainViewModel.setCurrentUser(updated) },
                            onShowMessage = { mainViewModel.emitMessage(it) },
                            onMessageCreator = { uid ->
                                inboxViewModel.openConversationWithUser(uid) { convId ->
                                    navController.navigate(ChatRoute(convId))
                                }
                            },
                            onVideoClick = { openVideoDetail(it) },
                            onOpenUserProfile = { uid -> navController.navigate(UserProfileRoute(uid)) }
                        )
                    }

                    composable<UserProfileRoute> { entry ->
                        val args = entry.toRoute<UserProfileRoute>()
                        ProfileScreen(
                            targetUserId = args.userId,
                            currentUser = currentUser,
                            profileViewModel = profileViewModel,
                            isOtherUserScreen = true,
                            onBack = { navController.popBackStack() },
                            onNavigateToAuth = { navController.navigate(AuthRoute) },
                            onNavigateToEditProfile = { navController.navigate(EditProfileRoute) },
                            onNavigateToSettings = { navController.navigate(SettingsRoute) },
                            onNavigateToAdmin = { navController.navigate(AdminRoute) },
                            onProfileUpdated = { updated -> mainViewModel.setCurrentUser(updated) },
                            onShowMessage = { mainViewModel.emitMessage(it) },
                            onMessageCreator = { uid ->
                                if (currentUser == null) {
                                    navController.navigate(AuthRoute)
                                } else {
                                    inboxViewModel.openConversationWithUser(uid) { convId ->
                                        navController.navigate(ChatRoute(convId))
                                    }
                                }
                            },
                            onVideoClick = { openVideoDetail(it) },
                            onOpenUserProfile = { uid -> navController.navigate(UserProfileRoute(uid)) }
                        )
                    }

                    composable<ChatRoute> { entry ->
                        val args = entry.toRoute<ChatRoute>()
                        ChatScreen(
                            conversationId = args.conversationId,
                            inboxViewModel = inboxViewModel,
                            currentUser = currentUser,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<AuthRoute> {
                        AuthScreen(
                            authViewModel = authViewModel,
                            onBack = { navController.popBackStack() },
                            onAuthenticated = { profile ->
                                mainViewModel.setCurrentUser(profile)
                                feedViewModel.loadFeed()
                                navController.popBackStack()
                            }
                        )
                    }

                    composable<EditProfileRoute> {
                        currentUser?.let { user ->
                            EditProfileScreen(
                                currentUser = user,
                                profileViewModel = profileViewModel,
                                onBack = { navController.popBackStack() },
                                onSaved = { updated -> mainViewModel.setCurrentUser(updated) }
                            )
                        }
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            currentUser = currentUser,
                            isDarkMode = isDarkMode,
                            languageCode = languageCode,
                            isSupabaseConfigured = mainViewModel.isSupabaseConfigured,
                            onToggleDarkMode = { mainViewModel.toggleDarkMode(it) },
                            onSelectLanguage = { mainViewModel.setLanguage(it) },
                            onSignOut = { mainViewModel.signOut() },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<WalletRoute> {
                        currentUser?.let { user ->
                            WalletScreen(
                                currentUser = user,
                                profileViewModel = profileViewModel,
                                onCoinsUpdated = { bal ->
                                    mainViewModel.setCurrentUser(user.copy(coinsBalance = bal))
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    composable<AdminRoute> {
                        AdminScreen(
                            profileViewModel = profileViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable<VideoPlayerRoute> { entry ->
                        val args = entry.toRoute<VideoPlayerRoute>()
                        VideoDetailPlayerScreen(
                            videoUrl = args.videoUrl,
                            title = args.title,
                            caption = args.caption,
                            username = args.username,
                            filterName = args.filterName,
                            trimStartMs = args.trimStartMs,
                            trimEndMs = args.trimEndMs,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BladeCreateNavBadge() {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(BladeRedDark, BladeRed, BladeRedAccent))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Create",
            tint = TextWhite,
            modifier = Modifier.size(22.dp)
        )
    }
}
