/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net

import android.app.Instrumentation
import android.content.Context
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProviderTest.TestNetworkCallback.CallbackEntry.OnUnavailable
import android.net.NetworkProviderTest.TestNetworkProvider.CallbackEntry.OnNetworkRequestWithdrawn
import android.net.NetworkProviderTest.TestNetworkProvider.CallbackEntry.OnNetworkRequested
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.test.InstrumentationRegistry
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.CompatUtil
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.isDevSdkInRange
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoMoreInteractions
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail

private const val DEFAULT_TIMEOUT_MS = 5000L
private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()
private val context: Context get() = InstrumentationRegistry.getContext()
private val PROVIDER_NAME = "NetworkProviderTest"

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.Q)
class NetworkProviderTest {
    @Rule @JvmField
    val mIgnoreRule = DevSdkIgnoreRule()
    private val mCm = context.getSystemService(ConnectivityManager::class.java)
    private val mHandlerThread = HandlerThread("${javaClass.simpleName} handler thread")

    @Before
    fun setUp() {
        instrumentation.getUiAutomation().adoptShellPermissionIdentity()
        mHandlerThread.start()
    }

    @After
    fun tearDown() {
        mHandlerThread.quitSafely()
        instrumentation.getUiAutomation().dropShellPermissionIdentity()
    }

    private class TestNetworkProvider(context: Context, looper: Looper) :
            NetworkProvider(context, looper, PROVIDER_NAME) {
        private val TAG = this::class.simpleName
        private val seenEvents = ArrayTrackRecord<CallbackEntry>().newReadHead()

        sealed class CallbackEntry {
            data class OnNetworkRequested(
                val request: NetworkRequest,
                val score: Int,
                val id: Int
            ) : CallbackEntry()
            data class OnNetworkRequestWithdrawn(val request: NetworkRequest) : CallbackEntry()
        }

        override fun onNetworkRequested(request: NetworkRequest, score: Int, id: Int) {
            Log.d(TAG, "onNetworkRequested $request, $score, $id")
            seenEvents.add(OnNetworkRequested(request, score, id))
        }

        override fun onNetworkRequestWithdrawn(request: NetworkRequest) {
            Log.d(TAG, "onNetworkRequestWithdrawn $request")
            seenEvents.add(OnNetworkRequestWithdrawn(request))
        }

        inline fun <reified T : CallbackEntry> eventuallyExpectCallbackThat(
            crossinline predicate: (T) -> Boolean
        ) = seenEvents.poll(DEFAULT_TIMEOUT_MS) { it is T && predicate(it) }
                ?: fail("Did not receive callback after ${DEFAULT_TIMEOUT_MS}ms")
    }

    private fun createNetworkProvider(ctx: Context = context): TestNetworkProvider {
        return TestNetworkProvider(ctx, mHandlerThread.looper)
    }

    // In S+ framework, do not run this test, since the provider will no longer receive
    // onNetworkRequested for every request. Instead, provider needs to
    // call {@code registerNetworkOffer} with the description of networks they
    // might have ability to setup, and expects {@link NetworkOfferCallback#onNetworkNeeded}.
    @IgnoreAfter(Build.VERSION_CODES.R)
    @Test
    fun testOnNetworkRequested() {
        val provider = createNetworkProvider()
        assertEquals(provider.getProviderId(), NetworkProvider.ID_NONE)
        mCm.registerNetworkProvider(provider)
        assertNotEquals(provider.getProviderId(), NetworkProvider.ID_NONE)

        val specifier = CompatUtil.makeTestNetworkSpecifier(
                UUID.randomUUID().toString())
        // Test network is not allowed to be trusted.
        val nr: NetworkRequest = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_TEST)
                .removeCapability(NET_CAPABILITY_TRUSTED)
                .setNetworkSpecifier(specifier)
                .build()
        val cb = ConnectivityManager.NetworkCallback()
        mCm.requestNetwork(nr, cb)
        provider.eventuallyExpectCallbackThat<OnNetworkRequested>() { callback ->
            callback.request.getNetworkSpecifier() == specifier &&
            callback.request.hasTransport(TRANSPORT_TEST)
        }

        val initialScore = 40
        val updatedScore = 60
        val nc = NetworkCapabilities().apply {
                addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                setNetworkSpecifier(specifier)
        }
        val lp = LinkProperties()
        val config = NetworkAgentConfig.Builder().build()
        val agent = object : NetworkAgent(context, mHandlerThread.looper, "TestAgent", nc, lp,
                initialScore, config, provider) {}
        agent.register()
        agent.markConnected()

        provider.eventuallyExpectCallbackThat<OnNetworkRequested>() { callback ->
            callback.request.getNetworkSpecifier() == specifier &&
            callback.score == initialScore &&
            callback.id == agent.providerId
        }

        agent.sendNetworkScore(updatedScore)
        provider.eventuallyExpectCallbackThat<OnNetworkRequested>() { callback ->
            callback.request.getNetworkSpecifier() == specifier &&
            callback.score == updatedScore &&
            callback.id == agent.providerId
        }

        mCm.unregisterNetworkCallback(cb)
        provider.eventuallyExpectCallbackThat<OnNetworkRequestWithdrawn>() { callback ->
            callback.request.getNetworkSpecifier() == specifier &&
            callback.request.hasTransport(TRANSPORT_TEST)
        }
        mCm.unregisterNetworkProvider(provider)
        // Provider id should be ID_NONE after unregister network provider
        assertEquals(provider.getProviderId(), NetworkProvider.ID_NONE)
        // unregisterNetworkProvider should not crash even if it's called on an
        // already unregistered provider.
        mCm.unregisterNetworkProvider(provider)
    }

    private class TestNetworkCallback : ConnectivityManager.NetworkCallback() {
        private val seenEvents = ArrayTrackRecord<CallbackEntry>().newReadHead()
        sealed class CallbackEntry {
            object OnUnavailable : CallbackEntry()
        }

        override fun onUnavailable() {
            seenEvents.add(OnUnavailable)
        }

        inline fun <reified T : CallbackEntry> expectCallback(
            crossinline predicate: (T) -> Boolean
        ) = seenEvents.poll(DEFAULT_TIMEOUT_MS) { it is T && predicate(it) }
    }

    @Test
    fun testDeclareNetworkRequestUnfulfillable() {
        val mockContext = mock(Context::class.java)
        doReturn(mCm).`when`(mockContext).getSystemService(Context.CONNECTIVITY_SERVICE)
        val provider = createNetworkProvider(mockContext)
        // ConnectivityManager not required at creation time after R
        if (!isDevSdkInRange(0, Build.VERSION_CODES.R)) {
            verifyNoMoreInteractions(mockContext)
        }

        mCm.registerNetworkProvider(provider)

        val specifier = CompatUtil.makeTestNetworkSpecifier(
                UUID.randomUUID().toString())
        val nr: NetworkRequest = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_TEST)
                .setNetworkSpecifier(specifier)
                .build()

        val cb = TestNetworkCallback()
        mCm.requestNetwork(nr, cb)
        provider.declareNetworkRequestUnfulfillable(nr)
        cb.expectCallback<OnUnavailable>() { nr.getNetworkSpecifier() == specifier }
        mCm.unregisterNetworkProvider(provider)
    }
}
