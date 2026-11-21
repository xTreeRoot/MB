package com.xtreeroot.mb.ui

import android.app.ActivityManager
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.account.QrSignInService
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.music.TrackPage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSignInDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication
    private val signedIn = SourceAccount("fixture", "Fixture Listener", null)

    @Test fun qrLoginRemainsProtectedWhileMbIsBackgroundedAndReleasesOnSuccess() = runBlocking {
        val confirmation = CompletableDeferred<SourceAccount>()
        val source = FixtureAccountSource { prompt ->
            prompt(SignInPrompt("https://fixture.invalid/scan"))
            confirmation.await()
        }
        withAccount(source) { activity, model ->
            eventually("QR foreground service did not start") { foregroundSignIn() }
            withContext(Dispatchers.Main) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            eventually("MB did not stop after opening Settings") { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            assertTrue(foregroundSignIn())
            confirmation.complete(signedIn)
            eventually("Login did not finish or release the service in the background") {
                model.account.value.verifiedFor(source) == signedIn && !foregroundSignIn()
            }
            assertNull(model.account.value.prompt)
        }
    }

    @Test fun failedQrIsRemovedAndAWebRetryDoesNotKeepTheForegroundService() = runBlocking {
        val failure = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<SourceAccount>()
        var first = true
        val source = FixtureAccountSource { prompt ->
            if (first) {
                first = false
                prompt(SignInPrompt("https://fixture.invalid/expired"))
                failure.await()
                throw IOException("Fixture QR expired")
            }
            prompt(SignInPrompt(null, message = "Open the sign-in page"))
            retry.await()
        }
        withAccount(source) { _, model ->
            eventually { model.account.value.prompt?.qrUrl != null }
            failure.complete(Unit)
            eventually { model.account.value.error == "Fixture QR expired" && !foregroundSignIn() }
            assertNull(model.account.value.prompt)
            withContext(Dispatchers.Main) { model.signIn() }
            eventually { model.account.value.prompt?.message == "Open the sign-in page" }
            assertFalse(foregroundSignIn())
            retry.complete(signedIn)
            eventually { model.account.value.verifiedFor(source) == signedIn }
            assertEquals(signedIn, model.account.value.account)
        }
    }

    @Test fun cancellingQrReleasesProtectionAndDiscardsLateConfirmation() = runBlocking {
        val confirmation = CompletableDeferred<SourceAccount>()
        val source = FixtureAccountSource { prompt ->
            prompt(SignInPrompt("https://fixture.invalid/cancel"))
            confirmation.await()
        }
        withAccount(source) { _, model ->
            eventually { foregroundSignIn() }
            withContext(Dispatchers.Main) { model.cancelSignIn() }
            confirmation.complete(signedIn)
            eventually { !foregroundSignIn() }
            assertFalse(model.account.value.signingIn)
            assertNull(model.account.value.prompt)
            assertNull(model.account.value.account)
        }
    }

    private suspend fun withAccount(source: AccountSource, action: suspend (MainActivity, AccountModel) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AccountModel::class.java]
                    .also { it.activate(source) }
            }
            eventually { activity.lifecycle.currentState == Lifecycle.State.RESUMED && model.account.value.canSignIn }
            withContext(Dispatchers.Main) { model.signIn() }
            action(activity, model)
        } finally {
            withContext(Dispatchers.Main) { store.clear(); activity.finish() }
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundSignIn(): Boolean = app.getSystemService(ActivityManager::class.java).getRunningServices(20)
        .any { it.service.className == QrSignInService::class.java.name && it.foreground }

    private suspend fun eventually(message: String = "Expected account state was not reached", condition: () -> Boolean) {
        val reached = withTimeoutOrNull(5_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(25)
            true
        }
        assertEquals(message, true, reached)
    }

    private class FixtureAccountSource(private val perform: suspend (suspend (SignInPrompt) -> Unit) -> SourceAccount) : AccountSource {
        override val id = "fixture"
        override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        override suspend fun account(): SourceAccount? = null
        override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit) = perform(prompt)
        override suspend fun signOut() = Unit
    }
}
