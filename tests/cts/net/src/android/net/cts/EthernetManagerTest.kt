/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.net.IpConfiguration
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.platform.test.annotations.AppModeFull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.SC_V2
import com.android.testutils.runAsShell
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNull
import kotlin.test.fail
import android.net.cts.EthernetManagerTest.EthernetStateListener.CallbackEntry.InterfaceStateChanged
import android.os.Handler
import android.os.HandlerExecutor
import android.os.Looper
import com.android.networkstack.apishim.common.EthernetManagerShim.InterfaceStateListener
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_ABSENT
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_DOWN
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_UP
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_CLIENT
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_NONE
import com.android.networkstack.apishim.EthernetManagerShimImpl
import java.util.concurrent.Executor
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 1000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private val DEFAULT_IP_CONFIGURATION = IpConfiguration(IpConfiguration.IpAssignment.DHCP,
    IpConfiguration.ProxySettings.NONE, null, null)

@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(AndroidJUnit4::class)
class EthernetManagerTest {
    // EthernetManager is not updatable before T, so tests do not need to be backwards compatible
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = SC_V2)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val em by lazy { EthernetManagerShimImpl.newInstance(context) }

    private val createdIfaces = ArrayList<TestNetworkInterface>()
    private val addedListeners = ArrayList<InterfaceStateListener>()

    private open class EthernetStateListener private constructor(
        private val history: ArrayTrackRecord<CallbackEntry>
    ) : InterfaceStateListener,
                TrackRecord<EthernetStateListener.CallbackEntry> by history {
        constructor() : this(ArrayTrackRecord())

        val events = history.newReadHead()

        sealed class CallbackEntry {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val configuration: IpConfiguration?
            ) : CallbackEntry()
        }

        override fun onInterfaceStateChanged(
            iface: String,
            state: Int,
            role: Int,
            cfg: IpConfiguration?
        ) {
            add(InterfaceStateChanged(iface, state, role, cfg))
        }

        fun <T : CallbackEntry> expectCallback(expected: T): T {
            val event = pollForNextCallback()
            assertEquals(expected, event)
            return event as T
        }

        fun expectCallback(iface: TestNetworkInterface, state: Int, role: Int) {
            expectCallback(InterfaceStateChanged(iface.interfaceName, state, role,
                if (state != STATE_ABSENT) DEFAULT_IP_CONFIGURATION else null))
        }

        fun pollForNextCallback(): CallbackEntry {
            return events.poll(TIMEOUT_MS) ?: fail("Did not receive callback after ${TIMEOUT_MS}ms")
        }

        fun assertNoCallback() {
            val cb = events.poll(NO_CALLBACK_TIMEOUT_MS)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    @Test
    public fun testCallbacks() {
        val executor = HandlerExecutor(Handler(Looper.getMainLooper()))

        // If an interface exists when the callback is registered, it is reported on registration.
        val iface = runAsShell(MANAGE_TEST_NETWORKS) {
            createInterface()
        }
        val listener = EthernetStateListener()
        addInterfaceStateListener(executor, listener)
        listener.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)

        // If an interface appears, existing callbacks see it.
        // TODO: fix the up/up/down/up callbacks and only send down/up.
        val iface2 = runAsShell(MANAGE_TEST_NETWORKS) {
            createInterface()
        }
        listener.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)

        // Removing interfaces first sends link down, then STATE_ABSENT/ROLE_NONE.
        removeInterface(iface)
        listener.expectCallback(iface, STATE_LINK_DOWN, ROLE_CLIENT)
        listener.expectCallback(iface, STATE_ABSENT, ROLE_NONE)

        removeInterface(iface2)
        listener.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_ABSENT, ROLE_NONE)
        listener.assertNoCallback()
    }

    @Before
    fun setUp() {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(true)
        }
    }

    @After
    fun tearDown() {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(false)
            for (iface in createdIfaces) {
                if (iface.fileDescriptor.fileDescriptor.valid()) iface.fileDescriptor.close()
            }
            for (listener in addedListeners) {
                em.removeInterfaceStateListener(listener)
            }
        }
    }

    private fun addInterfaceStateListener(executor: Executor, listener: InterfaceStateListener) {
        em.addInterfaceStateListener(executor, listener)
        addedListeners.add(listener)
    }

    private fun createInterface(): TestNetworkInterface {
        val tnm = context.getSystemService(TestNetworkManager::class.java)
        return tnm.createTapInterface(false /* bringUp */).also { createdIfaces.add(it) }
    }

    private fun removeInterface(iface: TestNetworkInterface) {
        iface.fileDescriptor.close()
        createdIfaces.remove(iface)
    }

    private fun doTestGetInterfaceList() {
        em.setIncludeTestInterfaces(true)

        // Create two test interfaces and check the return list contains the interface names.
        val iface1 = createInterface()
        val iface2 = createInterface()
        var ifaces = em.getInterfaceList()
        assertTrue(ifaces.size > 0)
        assertTrue(ifaces.contains(iface1.getInterfaceName()))
        assertTrue(ifaces.contains(iface2.getInterfaceName()))

        // Remove one existing test interface and check the return list doesn't contain the
        // removed interface name.
        removeInterface(iface1)
        ifaces = em.getInterfaceList()
        assertFalse(ifaces.contains(iface1.getInterfaceName()))
        assertTrue(ifaces.contains(iface2.getInterfaceName()))

        removeInterface(iface2)
    }

    @Test
    public fun testGetInterfaceList() {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            doTestGetInterfaceList()
        }
    }
}
