package com.disp06.rc.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class Page(val title: String, val body: String)

private val pages = listOf(
    Page("Your watch, as a sensor",
        "The TicWatch collects heart and motion signals in the background. This phone does all the analysis, keeps your diary and decides when to remind you."),
    Page("Stay in the loop",
        "Allow notifications so stress alerts and reminders can reach you on the phone too. Reminders on the watch work even when the phone is away."),
    Page("Keep the watch collecting",
        "On the watch, open Research Companion › Battery settings and exclude it from battery optimisation. Expect about 1.5 days per charge in Standard mode, longer in Eco."),
)

/** Three-screen onboarding (brief §5.1). */
@Composable
fun Onboarding(vm: MainViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val status by vm.status.collectAsStateWithLifecycle()
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { page = 2 }
    LaunchedEffect(Unit) { vm.refreshStatus() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.height(48.dp))
            AnimatedContent(page, label = "onboarding") { p ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(pages[p].title, style = MaterialTheme.typography.headlineLarge)
                    Text(pages[p].body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p == 0) Text(
                        status?.let { "✓ Watch found (${it.sourceName})" } ?: "Install the watch app and open it once — it will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.indices.forEach { i ->
                        Box(Modifier.size(8.dp).background(
                            if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                Button(
                    onClick = {
                        when {
                            page == 1 && Build.VERSION.SDK_INT >= 33 -> perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                            page < 2 -> page++
                            else -> vm.updateSettings { it.copy(onboarded = true) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (page == 2) "Get started" else if (page == 1) "Allow notifications" else "Next") }
                if (page == 1) TextButton(onClick = { page = 2 }, Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
    }
}
