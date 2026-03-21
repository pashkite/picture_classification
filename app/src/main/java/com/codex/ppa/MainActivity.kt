package com.codex.ppa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.codex.ppa.ui.MainViewModel
import com.codex.ppa.ui.MainViewModelFactory
import com.codex.ppa.ui.PersonalMediaSorterApp
import com.codex.ppa.ui.theme.PersonalMediaSorterTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PersonalMediaSorterTheme {
                PersonalMediaSorterApp(viewModel = viewModel)
            }
        }
    }
}
