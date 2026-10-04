package com.keyzai.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Helper ViewModel dengan factory lambda (tanpa Hilt).
 *
 * Implementasi dipisah jadi dua fungsi karena Kotlin 2.1.20 crash
 * ("Couldn't inline method call") bila fungsi inline memanggil fungsi
 * inline composable lain (viewModel(), remember()) di dalamnya.
 */
@Composable
fun <VM : ViewModel> rememberVmImpl(vmClass: Class<VM>, factory: () -> VM): VM {
    val storeOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "Tidak ada ViewModelStoreOwner di LocalViewModelStoreOwner"
    }
    // Factory baru tiap recomposition tidak masalah: ViewModelProvider hanya
    // memakainya saat ViewModel belum ada di store.
    return ViewModelProvider(
        storeOwner,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory() as T
        },
    )[vmClass]
}

@Composable
inline fun <reified VM : ViewModel> rememberVm(noinline factory: () -> VM): VM {
    return rememberVmImpl(VM::class.java, factory)
}
