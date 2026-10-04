package com.keyzai.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.playservices.auth.GetGoogleIdOption
import androidx.credentials.playservices.auth.GoogleIdTokenCredential
import java.util.UUID

/**
 * Login Google via Credential Manager.
 *
 * Meminta Google ID token dengan audience = Web OAuth client ID yang SAMA
 * dengan yang dipakai web (VITE_GOOGLE_CLIENT_ID). Worker memverifikasi
 * `aud` token terhadap GOOGLE_CLIENT_ID miliknya, sehingga token dari
 * aplikasi Android ini diterima TANPA perubahan backend.
 *
 * Catatan: jika Google melempar error developer (mis. 401/DEVELOPER_ERROR),
 * daftarkan SHA-1 signing cert aplikasi di Google Cloud Console pada
 * OAuth client Android dengan package name com.keyzai.app.
 */
object GoogleAuth {

    /** Web OAuth client ID KeyzAI (publik — sama dengan di frontend web). */
    const val WEB_CLIENT_ID =
        "227191802214-klcplrl89607it7obq9ne3ren73o9th9.apps.googleusercontent.com"

    class UserCancelled : Exception("Login dibatalkan")

    /**
     * Menampilkan pemilih akun Google sistem, mengembalikan Google ID token.
     */
    suspend fun signIn(context: Context): String {
        val credentialManager = CredentialManager.create(context)
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .setNonce(UUID.randomUUID().toString())
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val result = try {
            credentialManager.getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw UserCancelled()
        } catch (e: NoCredentialException) {
            throw Exception("Tidak ada akun Google di perangkat ini. Tambahkan akun Google dulu di Pengaturan.")
        }
        val credential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return credential.idToken
    }
}
