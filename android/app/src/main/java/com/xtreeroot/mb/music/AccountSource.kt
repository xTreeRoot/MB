package com.xtreeroot.mb.music

data class SourceAccount(val id: String, val name: String, val avatarUrl: String?, val page: String? = null,
                         val avatarHeaders: Map<String, String> = emptyMap())

enum class SignInStatus { Scan, Confirm, Refreshed, Message }

data class SignInPrompt(val qrUrl: String?, val status: SignInStatus = SignInStatus.Message, val message: String = "")

interface AccountSource : MusicSource {
    suspend fun supportsSignIn(): Boolean = true
    suspend fun supportsSignOut(): Boolean = true
    suspend fun account(): SourceAccount?
    suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount
    suspend fun signOut()
}
