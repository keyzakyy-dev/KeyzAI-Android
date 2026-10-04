package com.keyzai.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Helper ViewModel dengan factory lambda (tanpa Hilt).
 *
 * Memakai ViewModelProvider langsung (bukan viewModel() yang inline) untuk
 * menghindari backend crash Kotlin saat inlining ("Couldn't inline method call").
 */
@Composable
inline fun <reified VM : ViewModel> rememberVm(crossinline factory: () -> VM): VM {
    val storeOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "Tidak ada ViewModelStoreOwner di LocalViewModelStoreOwner"
    }
    val factoryObj = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory() as T
        }
    }
    return ViewModelProvider(storeOwner, factoryObj)[VM::class.java]
}
