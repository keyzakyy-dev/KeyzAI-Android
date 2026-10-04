package com.keyzai.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/** Helper ViewModel dengan factory lambda (tanpa Hilt). */
@Composable
inline fun <reified VM : ViewModel> rememberVm(crossinline factory: () -> VM): VM {
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory() as T
        },
    )
}
