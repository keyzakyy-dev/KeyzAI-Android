package com.keyzai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.keyzai.app.ui.AppNav
import com.keyzai.app.ui.theme.KeyzAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KeyzApplication).container
        setContent {
            KeyzAITheme {
                AppNav(container)
            }
        }
    }
}
