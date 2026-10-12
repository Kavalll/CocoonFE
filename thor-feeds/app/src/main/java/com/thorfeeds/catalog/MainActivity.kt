package com.thorfeeds.catalog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.thorfeeds.catalog.ThorFeedsApp
import com.thorfeeds.catalog.data.FolderScanner
import android.view.Display
import com.thorfeeds.catalog.ui.CatalogRoute
import com.thorfeeds.catalog.ui.FeedSolveHost
import com.thorfeeds.catalog.ui.CatalogViewModel
import com.thorfeeds.catalog.ui.DetailRoute
import com.thorfeeds.catalog.ui.DisplaySignals
import com.thorfeeds.catalog.ui.DualDisplayController
import com.thorfeeds.catalog.ui.SourcesRoute
import com.thorfeeds.catalog.ui.ThorFeedsTheme

class MainActivity : ComponentActivity() {
    override fun onDestroy() {
        ThorFeedsApp.graph(this).webFeeds.unbind(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThorFeedsApp.graph(this).webFeeds.bind(this)
        enableEdgeToEdge()
        setContent {
            ThorFeedsTheme {
                val vm: CatalogViewModel = viewModel()
                val nav = rememberNavController()
                val useSecond by vm.useSecondScreen.collectAsStateWithLifecycle()
                val secondScreenEnabled = rememberUpdatedState(useSecond)
                val preferPresentation by DisplaySignals.preferPresentation.collectAsStateWithLifecycle()
                var dual by remember { mutableStateOf(false) }
                val controller = remember {
                    DualDisplayController(
                        activity = this,
                        enabled = { secondScreenEnabled.value },
                        onDualChanged = { dual = it },
                    )
                }
                DisposableEffect(controller) {
                    controller.attach()
                    onDispose { controller.detach() }
                }
                LaunchedEffect(useSecond, preferPresentation) {
                    controller.sync()
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) vm.rescan()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(Unit) {
                    vm.refresh()
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(dual) {
                    if (dual && nav.currentDestination?.route == "detail") nav.popBackStack()
                }

                val folderLauncher = rememberLauncher()
                val importLauncher = rememberImporter(vm)
                val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
                val solve: (String, String) -> Unit = { sourceId, url -> vm.startSolve(sourceId, url, displayId) }

                Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = "catalog") {
                    composable("catalog") {
                        CatalogRoute(
                            dualScreen = dual,
                            onOpenDetail = {
                                controller.onSelectionChanged()
                                if (!dual) nav.navigate("detail")
                            },
                            onOpenSources = { nav.navigate("sources") },
                            onChooseFolder = { folderLauncher() },
                            onImportFeed = { importLauncher() },
                            onSolve = solve,
                        )
                    }
                    composable("detail") {
                        DetailRoute(
                            showBack = true,
                            onBack = { nav.popBackStack() },
                            onChooseFolder = { folderLauncher() },
                        )
                    }
                    composable("sources") {
                        SourcesRoute(
                            onBack = { nav.popBackStack() },
                            onChooseFolder = { folderLauncher() },
                            onImportFeed = { importLauncher() },
                            onSolve = solve,
                        )
                    }
                }
                FeedSolveHost(displayId = displayId)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ComponentActivity.rememberLauncher(): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val granted = result.data?.flags ?: FolderScanner.TREE_FLAGS
        val take = granted and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                if (take == 0) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else take,
            )
        } catch (_: SecurityException) {
            return@rememberLauncherForActivityResult
        }
        val vm = androidx.lifecycle.ViewModelProvider(this)[CatalogViewModel::class.java]
        vm.setFolder(uri.toString())
    }
    return {
        launcher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(FolderScanner.TREE_FLAGS),
        )
    }
}

@androidx.compose.runtime.Composable
private fun ComponentActivity.rememberImporter(vm: CatalogViewModel): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers only grant temporary read access. Try to parse anyway.
        }
        vm.importLocal(uri.toString(), displayName(uri))
    }
    return {
        launcher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/json", "text/plain", "application/octet-stream"),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
        )
    }
}

private fun ComponentActivity.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index) ?: "Imported feed"
        }
    }
    return "Imported feed"
}
