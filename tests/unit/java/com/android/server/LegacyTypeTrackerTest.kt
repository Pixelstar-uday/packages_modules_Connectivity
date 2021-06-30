/*
 * Copyright (C) 2019 The Android Open Source Project
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

// Don't warn about deprecated types anywhere in this test, because LegacyTypeTracker's very reason
// for existence is to power deprecated APIs. The annotation has to apply to the whole file because
// otherwise warnings will be generated by the imports of deprecated constants like TYPE_xxx.
@file:Suppress("DEPRECATION")

package com.android.server

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_WIFI
import android.content.pm.PackageManager.FEATURE_WIFI_DIRECT
import android.net.ConnectivityManager.TYPE_ETHERNET
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.ConnectivityManager.TYPE_MOBILE_CBS
import android.net.ConnectivityManager.TYPE_MOBILE_DUN
import android.net.ConnectivityManager.TYPE_MOBILE_EMERGENCY
import android.net.ConnectivityManager.TYPE_MOBILE_FOTA
import android.net.ConnectivityManager.TYPE_MOBILE_HIPRI
import android.net.ConnectivityManager.TYPE_MOBILE_IA
import android.net.ConnectivityManager.TYPE_MOBILE_IMS
import android.net.ConnectivityManager.TYPE_MOBILE_MMS
import android.net.ConnectivityManager.TYPE_MOBILE_SUPL
import android.net.ConnectivityManager.TYPE_VPN
import android.net.ConnectivityManager.TYPE_WIFI
import android.net.ConnectivityManager.TYPE_WIFI_P2P
import android.net.ConnectivityManager.TYPE_WIMAX
import android.net.EthernetManager
import android.net.NetworkInfo.DetailedState.CONNECTED
import android.net.NetworkInfo.DetailedState.DISCONNECTED
import android.os.Build
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import com.android.server.ConnectivityService.LegacyTypeTracker
import com.android.server.connectivity.NetworkAgentInfo
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

const val UNSUPPORTED_TYPE = TYPE_WIMAX

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class LegacyTypeTrackerTest {
    private val supportedTypes = arrayOf(TYPE_WIFI, TYPE_WIFI_P2P, TYPE_ETHERNET, TYPE_MOBILE,
            TYPE_MOBILE_SUPL, TYPE_MOBILE_MMS, TYPE_MOBILE_SUPL, TYPE_MOBILE_DUN, TYPE_MOBILE_HIPRI,
            TYPE_MOBILE_FOTA, TYPE_MOBILE_IMS, TYPE_MOBILE_CBS, TYPE_MOBILE_IA,
            TYPE_MOBILE_EMERGENCY, TYPE_VPN)

    private val mMockService = mock(ConnectivityService::class.java).apply {
        doReturn(false).`when`(this).isDefaultNetwork(any())
    }
    private val mPm = mock(PackageManager::class.java)
    private val mContext = mock(Context::class.java).apply {
        doReturn(true).`when`(mPm).hasSystemFeature(FEATURE_WIFI)
        doReturn(true).`when`(mPm).hasSystemFeature(FEATURE_WIFI_DIRECT)
        doReturn(mPm).`when`(this).packageManager
        doReturn(mock(EthernetManager::class.java)).`when`(this).getSystemService(
                Context.ETHERNET_SERVICE)
    }
    private val mTm = mock(TelephonyManager::class.java).apply {
        doReturn(true).`when`(this).isDataCapable
    }

    private fun makeTracker() = LegacyTypeTracker(mMockService).apply {
        loadSupportedTypes(mContext, mTm)
    }

    @Test
    fun testSupportedTypes() {
        val tracker = makeTracker()
        supportedTypes.forEach {
            assertTrue(tracker.isTypeSupported(it))
        }
        assertFalse(tracker.isTypeSupported(UNSUPPORTED_TYPE))
    }

    @Test
    fun testSupportedTypes_NoEthernet() {
        doReturn(null).`when`(mContext).getSystemService(Context.ETHERNET_SERVICE)
        assertFalse(makeTracker().isTypeSupported(TYPE_ETHERNET))
    }

    @Test
    fun testSupportedTypes_NoTelephony() {
        doReturn(false).`when`(mTm).isDataCapable
        val tracker = makeTracker()
        val nonMobileTypes = arrayOf(TYPE_WIFI, TYPE_WIFI_P2P, TYPE_ETHERNET, TYPE_VPN)
        nonMobileTypes.forEach {
            assertTrue(tracker.isTypeSupported(it))
        }
        supportedTypes.toSet().minus(nonMobileTypes).forEach {
            assertFalse(tracker.isTypeSupported(it))
        }
    }

    @Test
    fun testSupportedTypes_NoWifiDirect() {
        doReturn(false).`when`(mPm).hasSystemFeature(FEATURE_WIFI_DIRECT)
        val tracker = makeTracker()
        assertFalse(tracker.isTypeSupported(TYPE_WIFI_P2P))
        supportedTypes.toSet().minus(TYPE_WIFI_P2P).forEach {
            assertTrue(tracker.isTypeSupported(it))
        }
    }

    @Test
    fun testSupl() {
        val tracker = makeTracker()
        val mobileNai = mock(NetworkAgentInfo::class.java)
        tracker.add(TYPE_MOBILE, mobileNai)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai, CONNECTED, TYPE_MOBILE)
        reset(mMockService)
        tracker.add(TYPE_MOBILE_SUPL, mobileNai)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai, CONNECTED, TYPE_MOBILE_SUPL)
        reset(mMockService)
        tracker.remove(TYPE_MOBILE_SUPL, mobileNai, false /* wasDefault */)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai, DISCONNECTED, TYPE_MOBILE_SUPL)
        reset(mMockService)
        tracker.add(TYPE_MOBILE_SUPL, mobileNai)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai, CONNECTED, TYPE_MOBILE_SUPL)
        reset(mMockService)
        tracker.remove(mobileNai, false)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai, DISCONNECTED, TYPE_MOBILE_SUPL)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai, DISCONNECTED, TYPE_MOBILE)
    }

    @Test
    fun testAddNetwork() {
        val tracker = makeTracker()
        val mobileNai = mock(NetworkAgentInfo::class.java)
        val wifiNai = mock(NetworkAgentInfo::class.java)
        tracker.add(TYPE_MOBILE, mobileNai)
        tracker.add(TYPE_WIFI, wifiNai)
        assertSame(tracker.getNetworkForType(TYPE_MOBILE), mobileNai)
        assertSame(tracker.getNetworkForType(TYPE_WIFI), wifiNai)
        // Make sure adding a second NAI does not change the results.
        val secondMobileNai = mock(NetworkAgentInfo::class.java)
        tracker.add(TYPE_MOBILE, secondMobileNai)
        assertSame(tracker.getNetworkForType(TYPE_MOBILE), mobileNai)
        assertSame(tracker.getNetworkForType(TYPE_WIFI), wifiNai)
        // Make sure removing a network that wasn't added for this type is a no-op.
        tracker.remove(TYPE_MOBILE, wifiNai, false /* wasDefault */)
        assertSame(tracker.getNetworkForType(TYPE_MOBILE), mobileNai)
        assertSame(tracker.getNetworkForType(TYPE_WIFI), wifiNai)
        // Remove the top network for mobile and make sure the second one becomes the network
        // of record for this type.
        tracker.remove(TYPE_MOBILE, mobileNai, false /* wasDefault */)
        assertSame(tracker.getNetworkForType(TYPE_MOBILE), secondMobileNai)
        assertSame(tracker.getNetworkForType(TYPE_WIFI), wifiNai)
        // Make sure adding a network for an unsupported type does not register it.
        tracker.add(UNSUPPORTED_TYPE, mobileNai)
        assertNull(tracker.getNetworkForType(UNSUPPORTED_TYPE))
    }

    @Test
    fun testBroadcastOnDisconnect() {
        val tracker = makeTracker()
        val mobileNai1 = mock(NetworkAgentInfo::class.java)
        val mobileNai2 = mock(NetworkAgentInfo::class.java)
        doReturn(false).`when`(mMockService).isDefaultNetwork(mobileNai1)
        tracker.add(TYPE_MOBILE, mobileNai1)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai1, CONNECTED, TYPE_MOBILE)
        reset(mMockService)
        doReturn(false).`when`(mMockService).isDefaultNetwork(mobileNai2)
        tracker.add(TYPE_MOBILE, mobileNai2)
        verify(mMockService, never()).sendLegacyNetworkBroadcast(any(), any(), anyInt())
        tracker.remove(TYPE_MOBILE, mobileNai1, false /* wasDefault */)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai1, DISCONNECTED, TYPE_MOBILE)
        verify(mMockService).sendLegacyNetworkBroadcast(mobileNai2, CONNECTED, TYPE_MOBILE)
    }
}
