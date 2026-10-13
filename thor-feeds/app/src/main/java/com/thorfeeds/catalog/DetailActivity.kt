package com.thorfeeds.catalog

import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thorfeeds.catalog.ui.DetailRoute
import com.thorfeeds.catalog.ui.DisplaySignals
import com.thorfeeds.catalog.ui.DownloadsRoute
import com.thorfeeds.catalog.ui.SecondaryPage
import com.thorfeeds.catalog.ui.ThorFeedsTheme

class DetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expected = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        val actual = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (expected != -1 && actual != expected) {
            DisplaySignals.preferPresentation.value = true
            finish()
            return
        }
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (DisplaySignals.secondaryPage.value == SecondaryPage.Downloads) {
                        DisplaySignals.secondaryPage.value = SecondaryPage.Game
                    } else {
                        DisplaySignals.userClosedDetail = true
                        finish()
                    }
                }
            },
        )
        setContent {
            ThorFeedsTheme {
                val page by DisplaySignals.secondaryPage.collectAsState()
                if (page == SecondaryPage.Downloads) {
                    DownloadsRoute(showBack = true, onBack = {
                        DisplaySignals.secondaryPage.value = SecondaryPage.Game
                    })
                } else {
                    DetailRoute(
                        showBack = true,
                        onBack = {
                            DisplaySignals.userClosedDetail = true
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (DisplaySignals.secondaryPage.value == SecondaryPage.Downloads) {
                DisplaySignals.secondaryPage.value = SecondaryPage.Game
            } else {
                DisplaySignals.userClosedDetail = true
                finish()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        DisplaySignals.detailRunning.value = true
    }

    override fun onStop() {
        DisplaySignals.detailRunning.value = false
        super.onStop()
    }

    companion object {
        const val EXTRA_DISPLAY_ID = "expected_display_id"
    }
}
