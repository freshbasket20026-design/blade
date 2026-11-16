package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.bladeStrings
import com.example.data.model.UiState
import com.example.ui.components.formatCompactCount
import com.example.ui.theme.BladeCrimson
import com.example.ui.theme.BladeCyan
import com.example.ui.viewmodel.ProfileViewModel

@Composable
fun AdminScreen(
    profileViewModel: ProfileViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = bladeStrings()
    val uiState by profileViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        profileViewModel.loadAdminDashboard()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(strings.adminPanel, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(48.dp))
        }

        when (val admin = uiState.adminState) {
            is UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BladeCrimson)
                }
            }
            is UiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(admin.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is UiState.Success -> {
                val stats = admin.data
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AdminMetricCard("Users", formatCompactCount(stats.totalUsers), Modifier.weight(1f))
                            AdminMetricCard("Videos", formatCompactCount(stats.totalVideos), Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AdminMetricCard("Total Views", formatCompactCount(stats.totalViews.toInt()), Modifier.weight(1f))
                            AdminMetricCard("Total Likes", formatCompactCount(stats.totalLikes.toInt()), Modifier.weight(1f))
                        }
                    }

                    item {
                        Text("Content Reports (${stats.pendingReports.size})", style = MaterialTheme.typography.titleMedium)
                    }
                    if (stats.pendingReports.isEmpty()) {
                        item {
                            Text("No reports queued.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(stats.pendingReports, key = { it.id }) { report ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("${report.reason} • ${report.status}", style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { profileViewModel.adminResolveReport(report.id, "reviewed") }) {
                                            Text("Mark Reviewed")
                                        }
                                        OutlinedButton(onClick = { profileViewModel.adminResolveReport(report.id, "dismissed") }) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Creators & Verification", style = MaterialTheme.typography.titleMedium)
                    }
                    items(stats.recentUsers, key = { it.id }) { user ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("@${user.username}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${user.followersCount} followers • ${user.role}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { profileViewModel.adminToggleVerified(user.id, !user.isVerified) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Verified,
                                        contentDescription = "Toggle Verify",
                                        tint = if (user.isVerified) BladeCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text("Recent Videos Moderation", style = MaterialTheme.typography.titleMedium)
                    }
                    items(stats.recentVideos, key = { it.id }) { video ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(video.caption.ifBlank { "(No caption)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(
                                        "@${video.creator?.username ?: "user"} • ${video.viewsCount} views",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { profileViewModel.adminDeleteVideo(video.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Video", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminMetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}
