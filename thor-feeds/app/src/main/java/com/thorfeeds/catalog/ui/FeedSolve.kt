package com.thorfeeds.catalog.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thorfeeds.catalog.ThorFeedsApp
import com.thorfeeds.catalog.core.FeedChallengeException
import kotlinx.coroutines.CancellationException

/**
 * Hosts the single feed WebView. It stays hidden for a later refresh that
 * still has clearance, and covers this display when the user taps Solve check.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FeedSolveHost(displayId: Int, primary: Boolean) {
    val vm: CatalogViewModel = viewModel()
    val session by vm.solveSession.collectAsStateWithLifecycle()
    val targetedHere = session?.displayId == displayId
    val keepHidden = primary && session == null
    if (!targetedHere && !keepHidden) return

    val gate = ThorFeedsApp.graph(androidx.compose.ui.platform.LocalContext.current).webFeeds
    Column(
        Modifier
            .then(if (targetedHere) Modifier.fillMaxSize() else Modifier.size(1.dp))
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (targetedHere) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Solve check", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Complete the check in this page. The feed is read here when it loads.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = vm::cancelSolve) { Text("Close") }
            }
        }
        AndroidView(
            modifier = if (targetedHere) Modifier.fillMaxSize() else Modifier.size(1.dp),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    gate.attach(this)
                }
            },
            onRelease = { view -> gate.detach(view) },
        )
    }

    val active = session
    if (targetedHere && active != null) {
        LaunchedEffect(active.sourceId, active.url) {
            try {
                val body = gate.load(active.url, interactive = true)
                vm.completeSolve(active.sourceId, body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: FeedChallengeException) {
                vm.noteSolveFailure(error.message ?: "The check is still open.")
            } catch (error: Exception) {
                vm.noteSolveFailure(error.message ?: "Couldn't read the feed after the check.")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { gate.cancelLoad() }
    }
}
