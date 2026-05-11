package com.aeriotv.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aeriotv.android.ui.theme.AerioTVTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug / future deep-link entry: pass --es url "https://..." from adb to
        // auto-load a playlist without typing. Production deep-link handling can
        // reuse this path with a custom URI scheme in the manifest.
        val initialUrl = intent?.getStringExtra("url")
        setContent {
            AerioTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AerioTVNavHost(initialUrl = initialUrl)
                }
            }
        }
    }
}
