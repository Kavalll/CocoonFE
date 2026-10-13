package com.thorfeeds.catalog.ui

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.thorfeeds.catalog.DetailActivity
import com.thorfeeds.catalog.core.DisplayChoice
import kotlinx.coroutines.flow.MutableStateFlow

object DisplaySignals {
    var userClosedDetail: Boolean = false
    val preferPresentation = MutableStateFlow(false)
    val detailRunning = MutableStateFlow(false)
}

class DualDisplayController(
    private val activity: ComponentActivity,
    private val enabled: () -> Boolean,
    private val onDualChanged: (Boolean) -> Unit,
) : DisplayManager.DisplayListener, DefaultLifecycleObserver {
    private val manager = activity.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var presentation: Presentation? = null
    private var stopped = false
    private val reopenLater = Runnable {
        if (!DisplaySignals.userClosedDetail) sync()
    }

    fun attach() {
        manager.registerDisplayListener(this, handler)
        activity.lifecycle.addObserver(this)
        reopenForResume()
    }

    fun detach() {
        handler.removeCallbacks(reopenLater)
        manager.unregisterDisplayListener(this)
        activity.lifecycle.removeObserver(this)
        dismissPresentation()
    }

    fun onSelectionChanged() {
        DisplaySignals.userClosedDetail = false
        sync()
    }

    fun reopenForResume() {
        DisplaySignals.userClosedDetail = false
        sync()
        handler.removeCallbacks(reopenLater)
        handler.postDelayed(reopenLater, 400)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (stopped) {
            stopped = false
            DisplaySignals.userClosedDetail = false
        }
        sync()
        handler.removeCallbacks(reopenLater)
        handler.postDelayed(reopenLater, 400)
    }

    override fun onStop(owner: LifecycleOwner) {
        stopped = true
    }

    override fun onDisplayAdded(displayId: Int) = sync()

    override fun onDisplayRemoved(displayId: Int) = sync()

    override fun onDisplayChanged(displayId: Int) = sync()

    fun sync() {
        val secondary = secondaryDisplay()
        onDualChanged(secondary != null && enabled())
        if (secondary == null || !enabled()) {
            dismissPresentation()
            return
        }
        if (DisplaySignals.userClosedDetail) return
        if (DisplaySignals.preferPresentation.value) {
            showPresentation(secondary)
            return
        }
        if (DisplaySignals.detailRunning.value) return
        launchDetail(secondary)
    }

    private fun secondaryDisplay(): Display? {
        val currentId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        val presentationIds = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()
        val snapshots = manager.displays.map { display ->
            DisplayChoice.Snapshot(
                id = display.displayId,
                isOn = display.state == Display.STATE_ON,
                supportsPresentation = display.displayId in presentationIds,
            )
        }
        val chosen = DisplayChoice.chooseSecondary(currentId, snapshots) ?: return null
        return manager.getDisplay(chosen.id)
    }

    private fun launchDetail(display: Display) {
        val intent = Intent(activity, DetailActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(DetailActivity.EXTRA_DISPLAY_ID, display.displayId)
        }
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
        try {
            activity.startActivity(intent, options.toBundle())
        } catch (_: Exception) {
            DisplaySignals.preferPresentation.value = true
            showPresentation(display)
        }
    }

    private fun showPresentation(display: Display) {
        if (presentation?.isShowing == true && presentation?.display?.displayId == display.displayId) return
        dismissPresentation()
        val next = DetailPresentation(activity, display) {
            DisplaySignals.userClosedDetail = true
        }
        try {
            next.show()
            presentation = next
        } catch (_: WindowManager.InvalidDisplayException) {
            presentation = null
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }
}

class DetailPresentation(
    private val host: ComponentActivity,
    display: Display,
    private val onClose: () -> Unit,
) : Presentation(host, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent {
                ThorFeedsTheme {
                    DetailRoute(showBack = true, onBack = {
                        onClose()
                        dismiss()
                    })
                }
            }
        }
        setContentView(composeView)
    }
}
