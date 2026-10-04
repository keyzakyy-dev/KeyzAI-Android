package com.keyzai.app

import android.app.Application
import com.keyzai.app.data.ChatRepository
import com.keyzai.app.data.KeyzApi
import com.keyzai.app.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * DI sederhana: satu container per proses aplikasi.
 */
class AppContainer(context: android.content.Context) {

    val session = SessionStore(context.applicationContext)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Diemit saat API menjawab 401 — UI harus kembali ke layar login. */
    val authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var _repo: ChatRepository? = null
    val repo: ChatRepository get() = _repo!!

    val api: KeyzApi = KeyzApi(
        tokenProvider = { session.cachedToken },
        onUnauthorized = {
            scope.launch {
                session.clear()
                _repo?.clearLocal()
                authExpired.emit(Unit)
            }
        },
    )

    init {
        _repo = ChatRepository(api, session, scope)
    }

    suspend fun warmup() {
        session.loadCache()
    }
}

class KeyzApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runBlocking { container.warmup() }
    }
}
