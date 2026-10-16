package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.ProtocolGuideScreen
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class ProtocolGuideActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val theme by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamic by viewModel.dynamicTheme.collectAsStateWithLifecycle()
            WireturnTheme(themeMode = theme, dynamicColor = dynamic) {
                ProtocolGuideScreen(onBack = { finish() })
            }
        }
    }
}
