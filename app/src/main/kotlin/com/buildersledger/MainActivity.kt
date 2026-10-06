package com.buildersledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.buildersledger.ui.LedgerApp
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.theme.LedgerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LedgerViewModel by viewModels { LedgerViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LedgerTheme {
                LedgerApp(viewModel)
            }
        }
    }
}
