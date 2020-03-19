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

package android.net.wifi.cts;

import static android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
import static android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
import static android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
import static android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiUsabilityStatsEntry;
import android.support.test.uiautomator.UiDevice;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.compatibility.common.util.SystemUtil;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConnectedNetworkScorerTest extends AndroidTestCase {
    private WifiManager mWifiManager;
    private UiDevice mUiDevice;
    private boolean mWasVerboseLoggingEnabled;

    private static final int DURATION = 10_000;
    private static final int DURATION_SCREEN_TOGGLE = 2000;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        mWifiManager = getContext().getSystemService(WifiManager.class);
        assertNotNull(mWifiManager);

        // turn on verbose logging for tests
        mWasVerboseLoggingEnabled = ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.isVerboseLoggingEnabled());
        ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.setVerboseLoggingEnabled(true));

        if (!mWifiManager.isWifiEnabled()) setWifiEnabled(true);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        turnScreenOn();
        PollingCheck.check("Wifi not enabled", DURATION, () -> mWifiManager.isWifiEnabled());
        List<WifiConfiguration> savedNetworks = ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.getConfiguredNetworks());
        assertFalse("Need at least one saved network", savedNetworks.isEmpty());
        // Wait for wifi is to be connected
        PollingCheck.check(
                "Wifi not connected",
                DURATION,
                () -> mWifiManager.getConnectionInfo().getNetworkId() != -1);
        assertThat(mWifiManager.getConnectionInfo().getNetworkId()).isNotEqualTo(-1);
    }

    @Override
    protected void tearDown() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            super.tearDown();
            return;
        }
        if (!mWifiManager.isWifiEnabled()) setWifiEnabled(true);
        turnScreenOff();
        ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.setVerboseLoggingEnabled(mWasVerboseLoggingEnabled));
        super.tearDown();
    }

    private void setWifiEnabled(boolean enable) throws Exception {
        // now trigger the change using shell commands.
        SystemUtil.runShellCommand("svc wifi " + (enable ? "enable" : "disable"));
    }

    private void turnScreenOn() throws Exception {
        mUiDevice.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        mUiDevice.executeShellCommand("wm dismiss-keyguard");
        // Since the screen on/off intent is ordered, they will not be sent right now.
        Thread.sleep(DURATION_SCREEN_TOGGLE);
    }

    private void turnScreenOff() throws Exception {
        mUiDevice.executeShellCommand("input keyevent KEYCODE_SLEEP");
    }

    private static class TestUsabilityStatsListener implements
            WifiManager.OnWifiUsabilityStatsListener {
        private final CountDownLatch mCountDownLatch;
        public int seqNum;
        public boolean isSameBssidAndFre;
        public WifiUsabilityStatsEntry statsEntry;

        TestUsabilityStatsListener(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onWifiUsabilityStats(int seqNum, boolean isSameBssidAndFreq,
                WifiUsabilityStatsEntry statsEntry) {
            this.seqNum = seqNum;
            this.isSameBssidAndFre = isSameBssidAndFreq;
            this.statsEntry = statsEntry;
            mCountDownLatch.countDown();
        }
    }

    /**
     * Tests the {@link android.net.wifi.WifiUsabilityStatsEntry} retrieved from
     * {@link WifiManager.OnWifiUsabilityStatsListener}.
     */
    public void testWifiUsabilityStatsEntry() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        CountDownLatch countDownLatch = new CountDownLatch(1);
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        TestUsabilityStatsListener usabilityStatsListener =
                new TestUsabilityStatsListener(countDownLatch);
        try {
            uiAutomation.adoptShellPermissionIdentity();
            mWifiManager.addOnWifiUsabilityStatsListener(
                    Executors.newSingleThreadExecutor(), usabilityStatsListener);
            // Wait for new usability stats (while connected & screen on this is triggered
            // by platform periodically).
            assertThat(countDownLatch.await(DURATION, TimeUnit.MILLISECONDS)).isTrue();

            assertThat(usabilityStatsListener.statsEntry).isNotNull();
            WifiUsabilityStatsEntry statsEntry = usabilityStatsListener.statsEntry;

            assertThat(statsEntry.getTimeStampMillis()).isGreaterThan(0L);
            assertThat(statsEntry.getRssi()).isLessThan(0);
            assertThat(statsEntry.getLinkSpeedMbps()).isGreaterThan(0);
            assertThat(statsEntry.getTotalTxSuccess()).isGreaterThan(0L);
            assertThat(statsEntry.getTotalTxRetries()).isAtLeast(0L);
            assertThat(statsEntry.getTotalTxBad()).isAtLeast(0L);
            assertThat(statsEntry.getTotalRxSuccess()).isAtLeast(0L);
            assertThat(statsEntry.getTotalRadioOnTimeMillis()).isGreaterThan(0L);
            assertThat(statsEntry.getTotalRadioTxTimeMillis()).isGreaterThan(0L);
            assertThat(statsEntry.getTotalRadioRxTimeMillis()).isGreaterThan(0L);
            assertThat(statsEntry.getTotalScanTimeMillis()).isGreaterThan(0L);
            assertThat(statsEntry.getTotalNanScanTimeMillis()).isAtLeast(0L);
            assertThat(statsEntry.getTotalBackgroundScanTimeMillis()).isAtLeast(0L);
            assertThat(statsEntry.getTotalRoamScanTimeMillis()).isAtLeast(0L);
            assertThat(statsEntry.getTotalPnoScanTimeMillis()).isAtLeast(0L);
            assertThat(statsEntry.getTotalHotspot2ScanTimeMillis()).isAtLeast(0L);
            assertThat(statsEntry.getTotalCcaBusyFreqTimeMillis()).isAtLeast(0L);
            assertThat(statsEntry.getTotalRadioOnTimeMillis()).isGreaterThan(0L);
            assertThat(statsEntry.getTotalBeaconRx()).isGreaterThan(0L);
            assertThat(statsEntry.getProbeStatusSinceLastUpdate())
                    .isAnyOf(PROBE_STATUS_SUCCESS,
                            PROBE_STATUS_FAILURE,
                            PROBE_STATUS_NO_PROBE,
                            PROBE_STATUS_UNKNOWN);
            // -1 is default value for some of these fields if they're not available.
            assertThat(statsEntry.getProbeElapsedTimeSinceLastUpdateMillis()).isAtLeast(-1);
            assertThat(statsEntry.getProbeMcsRateSinceLastUpdate()).isAtLeast(-1);
            assertThat(statsEntry.getRxLinkSpeedMbps()).isAtLeast(-1);
            // no longer populated, return default value.
            assertThat(statsEntry.getCellularDataNetworkType())
                    .isAnyOf(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                            TelephonyManager.NETWORK_TYPE_GPRS,
                            TelephonyManager.NETWORK_TYPE_EDGE,
                            TelephonyManager.NETWORK_TYPE_UMTS,
                            TelephonyManager.NETWORK_TYPE_CDMA,
                            TelephonyManager.NETWORK_TYPE_EVDO_0,
                            TelephonyManager.NETWORK_TYPE_EVDO_A,
                            TelephonyManager.NETWORK_TYPE_1xRTT,
                            TelephonyManager.NETWORK_TYPE_HSDPA,
                            TelephonyManager.NETWORK_TYPE_HSUPA,
                            TelephonyManager.NETWORK_TYPE_HSPA,
                            TelephonyManager.NETWORK_TYPE_IDEN,
                            TelephonyManager.NETWORK_TYPE_EVDO_B,
                            TelephonyManager.NETWORK_TYPE_LTE,
                            TelephonyManager.NETWORK_TYPE_EHRPD,
                            TelephonyManager.NETWORK_TYPE_HSPAP,
                            TelephonyManager.NETWORK_TYPE_GSM,
                            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
                            TelephonyManager.NETWORK_TYPE_IWLAN,
                            TelephonyManager.NETWORK_TYPE_NR);
            assertThat(statsEntry.getCellularSignalStrengthDbm()).isAtMost(0);
            assertThat(statsEntry.getCellularSignalStrengthDb()).isAtMost(0);
            assertThat(statsEntry.isSameRegisteredCell()).isFalse();
        } finally {
            mWifiManager.removeOnWifiUsabilityStatsListener(usabilityStatsListener);
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Tests the {@link android.net.wifi.WifiManager#updateWifiUsabilityScore(int, int, int)}
     */
    public void testUpdateWifiUsabilityScore() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity();
            // update scoring with dummy values.
            mWifiManager.updateWifiUsabilityScore(0, 50, 50);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

}
