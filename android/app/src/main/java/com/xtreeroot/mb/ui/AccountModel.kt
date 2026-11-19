package com.xtreeroot.mb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AccountState(
    val account: SourceAccount? = null,
    val loading: Boolean = false,
    val signingIn: Boolean = false,
    val prompt: SignInPrompt? = null,
    val error: String? = null,
    val canSignIn: Boolean = false,
    val canSignOut: Boolean = false,
    val verifiedSource: AccountSource? = null,
) {
    fun verifiedFor(source: AccountSource?): SourceAccount? = account.takeIf {
        source != null && verifiedSource === source && !loading && !signingIn && error == null
    }
}

internal class AccountModel(application: Application) : AndroidViewModel(application) {
    private var source: AccountSource? = null
    private val state = MutableStateFlow(AccountState())
    private var job: Job? = null
    private var operation = 0L
    val account = state.asStateFlow()

    fun activate(next: AccountSource?) {
        if (source === next) return
        stop()
        source = next
        state.value = AccountState()
        refresh()
    }

    fun refresh() = launch(false) { active, generation ->
        val canSignIn = active.supportsSignIn()
        val canSignOut = active.supportsSignOut()
        if (generation == operation) state.update { it.copy(canSignIn = canSignIn, canSignOut = canSignOut) }
        active.account()
    }

    fun signIn() {
        if (!state.value.canSignIn) return
        launch(true) { active, generation ->
            active.signIn { prompt ->
                if (generation == operation) state.update { it.copy(prompt = prompt) }
            }
        }
    }

    fun cancelSignIn() {
        if (!state.value.signingIn) return
        stop()
        state.update { it.copy(loading = false, signingIn = false, prompt = null, error = null) }
    }

    fun signOut() {
        if (state.value.canSignOut) launch(false) { active, _ -> active.signOut(); null }
    }

    private fun stop() {
        operation++
        job?.cancel()
    }

    private fun launch(login: Boolean, work: suspend (AccountSource, Long) -> SourceAccount?) {
        val active = source ?: return
        stop()
        val generation = operation
        state.update { it.copy(loading = true, signingIn = login, prompt = null, error = null) }
        job = viewModelScope.launch {
            try {
                val result = work(active, generation)
                ensureActive()
                if (generation == operation) state.update {
                    it.copy(account = result, loading = false, signingIn = false, prompt = null, verifiedSource = active)
                }
            } catch (timedOut: TimeoutCancellationException) {
                if (generation == operation) state.update { it.copy(loading = false, error = "Sign-in timed out") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                if (generation == operation) state.update { it.copy(loading = false, error = failure.message ?: "账号请求失败") }
            }
        }
    }
}
