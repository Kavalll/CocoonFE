package com.thorfeeds.catalog.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thorfeeds.catalog.ThorFeedsApp
import com.thorfeeds.catalog.core.FeedChallengeException
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException

/**
 * Shown only after Solve check. The WebView instance is kept until the feed
 * JSON arrives or the user closes the page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FeedSolveHost() {
    val vm: CatalogViewModel = viewModel()
    val session by vm.solveSession.collectAsStateWithLifecycle()
    val active = session ?: return
    val context = LocalContext.current
    val gate = ThorFeedsApp.graph(context).webFeeds

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("Solve check", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Complete the checkbox on this page. It stays here until the feed loads or you close it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::cancelSolve) { Text("Close") }
        }
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(active.url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Open in browser") }
        Text(
            "If this page keeps asking you to recheck the box, open the feed in the browser. When the browser shows the feed, use Import JSON in Feeds. The browser does not share its clearance with downloads.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val activity = findActivity(viewContext) ?: findActivity(context)
                    ?: return@AndroidView WebView(viewContext)
                val webView = gate.interactiveWebView(activity)
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                webView
            },
            update = { },
        )
    }

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

private fun findActivity(context: Context): android.app.Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}
