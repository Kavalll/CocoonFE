package com.thorfeeds.catalog

import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thorfeeds.catalog.ui.DetailRoute
import com.thorfeeds.catalog.ui.DisplaySignals
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
                    DisplaySignals.userClosedDetail = true
                    finish()
                }
            },
        )
        setContent {
            ThorFeedsTheme {
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
