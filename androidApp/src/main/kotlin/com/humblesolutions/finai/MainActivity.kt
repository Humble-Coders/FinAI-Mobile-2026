package com.humblesolutions.finai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.humblesolutions.finai.ui.PlaceholderScreen
import com.humblesolutions.finai.ui.theme.FinAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FinAiTheme {
                PlaceholderScreen()
            }
        }
    }
}

@Preview
@Composable
private fun PlaceholderScreenPreview() {
    FinAiTheme {
        PlaceholderScreen()
    }
}
