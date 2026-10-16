package com.wireturn.app.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wireturn.app.ui.screens.CreateProfileScreen
import com.wireturn.app.ui.showExclusiveToast
import com.wireturn.app.R
import com.wireturn.app.ui.theme.WireturnTheme
import com.wireturn.app.viewmodel.MainViewModel

class CreateProfileActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !viewModel.isInitialized.value }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicTheme by viewModel.dynamicTheme.collectAsStateWithLifecycle()

            WireturnTheme(themeMode = themeMode, dynamicColor = dynamicTheme) {
                CreateProfileScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onSelectType = selectKernel@{ type, configJson, name ->
                        val intent = when (type) {
                            "WebDAV" -> android.content.Intent(this, com.wireturn.app.ui.activities.kernel.WebdavConfigActivity::class.java)
                            "MasterDnsVPN" -> android.content.Intent(this, com.wireturn.app.ui.activities.kernel.MasterDnsConfigActivity::class.java)
                            "VPN" -> android.content.Intent(this, com.wireturn.app.ui.activities.kernel.VpnConfigActivity::class.java)
                            "qWDTT" -> android.content.Intent(this, com.wireturn.app.ui.activities.kernel.QwdttConfigActivity::class.java)
                            else -> null
                        }
                        intent?.let {
                            it.putExtra("EXTRA_PROFILE_NAME", name)
                            if (configJson != null) it.putExtra("EXTRA_CONFIG_JSON", configJson)
                            startActivity(it)
                        }
                    }
                )
            }
        }
    }
}
