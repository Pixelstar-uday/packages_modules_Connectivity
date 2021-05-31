/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkStats.IFACE_ALL;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.NetworkTemplate.buildTemplateWifi;
import static android.net.TrafficStats.UID_REMOVED;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_POLL;
import static com.android.server.net.NetworkStatsService.packUidAndTag;
import static com.android.server.net.NetworkStatsService.unpackTag;
import static com.android.server.net.NetworkStatsService.unpackUid;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;

import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.IConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.INetworkManagementService;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.TrustedTime;

import com.android.server.net.NetworkStatsService;
import com.android.server.net.NetworkStatsService.NetworkStatsSettings;

import org.easymock.EasyMock;

import java.io.File;

/**
 * Tests for {@link NetworkStatsService}.
 */
@LargeTest
public class NetworkStatsServiceTest extends AndroidTestCase {
    private static final String TAG = "NetworkStatsServiceTest";

    private static final String TEST_IFACE = "test0";
    private static final long TEST_START = 1194220800000L;

    private static final String IMSI_1 = "310004";
    private static final String IMSI_2 = "310260";

    private static NetworkTemplate sTemplateWifi = buildTemplateWifi();
    private static NetworkTemplate sTemplateImsi1 = buildTemplateMobileAll(IMSI_1);
    private static NetworkTemplate sTemplateImsi2 = buildTemplateMobileAll(IMSI_2);

    private static final int UID_RED = 1001;
    private static final int UID_BLUE = 1002;
    private static final int UID_GREEN = 1003;

    private BroadcastInterceptingContext mServiceContext;
    private File mStatsDir;

    private INetworkManagementService mNetManager;
    private IAlarmManager mAlarmManager;
    private TrustedTime mTime;
    private NetworkStatsSettings mSettings;
    private IConnectivityManager mConnManager;

    private NetworkStatsService mService;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mServiceContext = new BroadcastInterceptingContext(getContext());
        mStatsDir = getContext().getFilesDir();

        mNetManager = createMock(INetworkManagementService.class);
        mAlarmManager = createMock(IAlarmManager.class);
        mTime = createMock(TrustedTime.class);
        mSettings = createMock(NetworkStatsSettings.class);
        mConnManager = createMock(IConnectivityManager.class);

        mService = new NetworkStatsService(
                mServiceContext, mNetManager, mAlarmManager, mTime, mStatsDir, mSettings);
        mService.bindConnectivityManager(mConnManager);

        expectDefaultSettings();
        expectSystemReady();

        replay();
        mService.systemReady();
        verifyAndReset();

    }

    @Override
    public void tearDown() throws Exception {
        for (File file : mStatsDir.listFiles()) {
            file.delete();
        }

        mServiceContext = null;
        mStatsDir = null;

        mNetManager = null;
        mAlarmManager = null;
        mTime = null;
        mSettings = null;
        mConnManager = null;

        mService = null;

        super.tearDown();
    }

    public void testNetworkStatsWifi() throws Exception {
        long elapsedRealtime = 0;

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 1024L, 1L, 2048L, 2L));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 1L, 2048L, 2L, 0);
        verifyAndReset();

        // and bump forward again, with counters going higher. this is
        // important, since polling should correctly subtract last snapshot.
        elapsedRealtime += DAY_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 4096L, 4L, 8192L, 8L));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4096L, 4L, 8192L, 8L, 0);
        verifyAndReset();

    }

    public void testStatsRebootPersist() throws Exception {
        long elapsedRealtime = 0;
        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));

        // verify service has empty history for wifi
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 1024L, 8L, 2048L, 16L));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 2)
                .addValues(TEST_IFACE, UID_RED, TAG_NONE, 512L, 4L, 256L, 2L)
                .addValues(TEST_IFACE, UID_BLUE, TAG_NONE, 128L, 1L, 128L, 1L));

        mService.incrementOperationCount(UID_RED, TAG_NONE, 20);
        mService.incrementOperationCount(UID_BLUE, TAG_NONE, 10);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 1024L, 8L, 2048L, 16L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 512L, 4L, 256L, 2L, 20);
        assertUidTotal(sTemplateWifi, UID_BLUE, 128L, 1L, 128L, 1L, 10);
        verifyAndReset();

        // graceful shutdown system, which should trigger persist of stats, and
        // clear any values in memory.
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SHUTDOWN));

        // talk with zombie service to assert stats have gone; and assert that
        // we persisted them to file.
        expectDefaultSettings();
        replay();
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        verifyAndReset();

        assertStatsFilesExist(true);

        // boot through serviceReady() again
        expectDefaultSettings();
        expectSystemReady();

        replay();
        mService.systemReady();

        // after systemReady(), we should have historical stats loaded again
        assertNetworkTotal(sTemplateWifi, 1024L, 8L, 2048L, 16L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 512L, 4L, 256L, 2L, 20);
        assertUidTotal(sTemplateWifi, UID_BLUE, 128L, 1L, 128L, 1L, 10);
        verifyAndReset();

    }

    public void testStatsBucketResize() throws Exception {
        long elapsedRealtime = 0;
        NetworkStatsHistory history = null;

        assertStatsFilesExist(false);

        // pretend that wifi network comes online; service should ask about full
        // network state, and poll any existing interfaces before updating.
        expectTime(TEST_START + elapsedRealtime);
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // modify some number on wifi, and trigger poll event
        elapsedRealtime += 2 * HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 512L, 4L, 512L, 4L));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        history = mService.getHistoryForNetwork(sTemplateWifi);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, 512L, 4L, 512L, 4L, 0);
        assertEquals(HOUR_IN_MILLIS, history.getBucketDuration());
        assertEquals(2, history.size());
        verifyAndReset();

        // now change bucket duration setting and trigger another poll with
        // exact same values, which should resize existing buckets.
        expectTime(TEST_START + elapsedRealtime);
        expectSettings(0L, 30 * MINUTE_IN_MILLIS, WEEK_IN_MILLIS);
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify identical stats, but spread across 4 buckets now
        history = mService.getHistoryForNetwork(sTemplateWifi);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, 512L, 4L, 512L, 4L, 0);
        assertEquals(30 * MINUTE_IN_MILLIS, history.getBucketDuration());
        assertEquals(4, history.size());
        verifyAndReset();

    }

    public void testUidStatsAcrossNetworks() throws Exception {
        long elapsedRealtime = 0;

        // pretend first mobile network comes online
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_1));
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // create some traffic on first network
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 2048L, 16L, 512L, 4L));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 3)
                .addValues(TEST_IFACE, UID_RED, TAG_NONE, 1536L, 12L, 512L, 4L)
                .addValues(TEST_IFACE, UID_RED, 0xF00D, 512L, 4L, 512L, 4L)
                .addValues(TEST_IFACE, UID_BLUE, TAG_NONE, 512L, 4L, 0L, 0L));

        mService.incrementOperationCount(UID_RED, TAG_NONE, 15);
        mService.incrementOperationCount(UID_RED, 0xF00D, 10);
        mService.incrementOperationCount(UID_BLUE, TAG_NONE, 5);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 512L, 4L, 15);
        assertUidTotal(sTemplateImsi1, UID_BLUE, 512L, 4L, 0L, 0L, 5);
        verifyAndReset();

        // now switch networks; this also tests that we're okay with interfaces
        // disappearing, to verify we don't count backwards.
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_2));
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // create traffic on second network
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 128L, 1L, 1024L, 8L));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_BLUE, TAG_NONE, 128L, 1L, 1024L, 8L));

        mService.incrementOperationCount(UID_BLUE, TAG_NONE, 10);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify original history still intact
        assertNetworkTotal(sTemplateImsi1, 2048L, 16L, 512L, 4L, 0);
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 512L, 4L, 15);
        assertUidTotal(sTemplateImsi1, UID_BLUE, 512L, 4L, 0L, 0L, 5);

        // and verify new history also recorded under different template, which
        // verifies that we didn't cross the streams.
        assertNetworkTotal(sTemplateImsi2, 128L, 1L, 1024L, 8L, 0);
        assertNetworkTotal(sTemplateWifi, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateImsi2, UID_BLUE, 128L, 1L, 1024L, 8L, 10);
        verifyAndReset();

    }

    public void testUidRemovedIsMoved() throws Exception {
        long elapsedRealtime = 0;

        // pretend that network comes online
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // create some traffic
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_ALL, TAG_NONE, 4128L, 258L, 544L, 34L));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_RED, TAG_NONE, 16L, 1L, 16L, 1L)
                .addValues(TEST_IFACE, UID_BLUE, TAG_NONE, 4096L, 258L, 512L, 32L)
                .addValues(TEST_IFACE, UID_GREEN, TAG_NONE, 16L, 1L, 16L, 1L));

        mService.incrementOperationCount(UID_RED, TAG_NONE, 10);
        mService.incrementOperationCount(UID_BLUE, TAG_NONE, 15);
        mService.incrementOperationCount(UID_GREEN, TAG_NONE, 5);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertNetworkTotal(sTemplateWifi, 4128L, 258L, 544L, 34L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 16L, 1L, 16L, 1L, 10);
        assertUidTotal(sTemplateWifi, UID_BLUE, 4096L, 258L, 512L, 32L, 15);
        assertUidTotal(sTemplateWifi, UID_GREEN, 16L, 1L, 16L, 1L, 5);
        verifyAndReset();

        // now pretend two UIDs are uninstalled, which should migrate stats to
        // special "removed" bucket.
        expectDefaultSettings();
        replay();
        final Intent intent = new Intent(ACTION_UID_REMOVED);
        intent.putExtra(EXTRA_UID, UID_BLUE);
        mServiceContext.sendBroadcast(intent);
        intent.putExtra(EXTRA_UID, UID_RED);
        mServiceContext.sendBroadcast(intent);

        // existing uid and total should remain unchanged; but removed UID
        // should be gone completely.
        assertNetworkTotal(sTemplateWifi, 4128L, 258L, 544L, 34L, 0);
        assertUidTotal(sTemplateWifi, UID_RED, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateWifi, UID_BLUE, 0L, 0L, 0L, 0L, 0);
        assertUidTotal(sTemplateWifi, UID_GREEN, 16L, 1L, 16L, 1L, 5);
        assertUidTotal(sTemplateWifi, UID_REMOVED, 4112L, 259L, 528L, 33L, 25);
        verifyAndReset();

    }

    public void testUid3g4gCombinedByTemplate() throws Exception {
        long elapsedRealtime = 0;

        // pretend that network comes online
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildMobile3gState(IMSI_1));
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // create some traffic
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_RED, TAG_NONE, 1024L, 8L, 1024L, 8L)
                .addValues(TEST_IFACE, UID_RED, 0xF00D, 512L, 4L, 512L, 4L));

        mService.incrementOperationCount(UID_RED, TAG_NONE, 10);
        mService.incrementOperationCount(UID_RED, 0xF00D, 5);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertUidTotal(sTemplateImsi1, UID_RED, 1024L, 8L, 1024L, 8L, 10);
        verifyAndReset();

        // now switch over to 4g network
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildMobile4gState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();

        // create traffic on second network
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_RED, TAG_NONE, 512L, 4L, 256L, 2L));

        mService.incrementOperationCount(UID_RED, TAG_NONE, 5);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify that ALL_MOBILE template combines both
        assertUidTotal(sTemplateImsi1, UID_RED, 1536L, 12L, 1280L, 10L, 15);

        verifyAndReset();

    }
    
    public void testPackedUidAndTag() throws Exception {
        assertEquals(0x0000000000000000L, packUidAndTag(0, 0x0));
        assertEquals(0x000003E900000000L, packUidAndTag(1001, 0x0));
        assertEquals(0x000003E90000F00DL, packUidAndTag(1001, 0xF00D));

        long packed;
        packed = packUidAndTag(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, unpackUid(packed));
        assertEquals(Integer.MIN_VALUE, unpackTag(packed));

        packed = packUidAndTag(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, unpackUid(packed));
        assertEquals(Integer.MAX_VALUE, unpackTag(packed));

        packed = packUidAndTag(10005, 0xFFFFFFFF);
        assertEquals(10005, unpackUid(packed));
        assertEquals(0xFFFFFFFF, unpackTag(packed));
        
    }

    public void testSummaryForAllUid() throws Exception {
        long elapsedRealtime = 0;

        // pretend that network comes online
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkState(buildWifiState());
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // bootstrap with full polling event to prime stats
        performBootstrapPoll(TEST_START, elapsedRealtime);

        // create some traffic for two apps
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_RED, TAG_NONE, 50L, 5L, 50L, 5L)
                .addValues(TEST_IFACE, UID_RED, 0xF00D, 10L, 1L, 10L, 1L)
                .addValues(TEST_IFACE, UID_BLUE, TAG_NONE, 1024L, 8L, 512L, 4L));

        mService.incrementOperationCount(UID_RED, TAG_NONE, 5);
        mService.incrementOperationCount(UID_RED, 0xF00D, 1);
        mService.incrementOperationCount(UID_BLUE, TAG_NONE, 10);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // verify service recorded history
        assertUidTotal(sTemplateWifi, UID_RED, 50L, 5L, 50L, 5L, 5);
        assertUidTotal(sTemplateWifi, UID_BLUE, 1024L, 8L, 512L, 4L, 10);
        verifyAndReset();

        // now create more traffic in next hour, but only for one app
        elapsedRealtime += HOUR_IN_MILLIS;
        expectTime(TEST_START + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(new NetworkStats(elapsedRealtime, 1)
                .addValues(TEST_IFACE, UID_BLUE, TAG_NONE, 2048L, 16L, 1024L, 8L));

        mService.incrementOperationCount(UID_BLUE, TAG_NONE, 15);

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));

        // first verify entire history present
        NetworkStats stats = mService.getSummaryForAllUid(
                sTemplateWifi, Long.MIN_VALUE, Long.MAX_VALUE, true);
        assertEquals(3, stats.size());
        assertValues(stats, 0, IFACE_ALL, UID_RED, TAG_NONE, 50L, 5L, 50L, 5L, 5);
        assertValues(stats, 1, IFACE_ALL, UID_RED, 0xF00D, 10L, 1L, 10L, 1L, 1);
        assertValues(stats, 2, IFACE_ALL, UID_BLUE, TAG_NONE, 2048L, 16L, 1024L, 8L, 15);

        // now verify that recent history only contains one uid
        final long currentTime = TEST_START + elapsedRealtime;
        stats = mService.getSummaryForAllUid(
                sTemplateWifi, currentTime - HOUR_IN_MILLIS, currentTime, true);
        assertEquals(1, stats.size());
        assertValues(stats, 0, IFACE_ALL, UID_BLUE, TAG_NONE, 1024L, 8L, 512L, 4L, 5);

        verifyAndReset();
    }

    private void assertNetworkTotal(NetworkTemplate template, long rxBytes, long rxPackets,
            long txBytes, long txPackets, int operations) {
        final NetworkStatsHistory history = mService.getHistoryForNetwork(template);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, rxBytes, rxPackets, txBytes,
                txPackets, operations);
    }

    private void assertUidTotal(NetworkTemplate template, int uid, long rxBytes, long rxPackets,
            long txBytes, long txPackets, int operations) {
        final NetworkStatsHistory history = mService.getHistoryForUid(template, uid, TAG_NONE);
        assertValues(history, Long.MIN_VALUE, Long.MAX_VALUE, rxBytes, rxPackets, txBytes,
                txPackets, operations);
    }

    private void expectSystemReady() throws Exception {
        mAlarmManager.remove(isA(PendingIntent.class));
        expectLastCall().anyTimes();

        mAlarmManager.setInexactRepeating(
                eq(AlarmManager.ELAPSED_REALTIME), anyLong(), anyLong(), isA(PendingIntent.class));
        expectLastCall().atLeastOnce();
    }

    private void expectNetworkState(NetworkState... state) throws Exception {
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
    }

    private void expectNetworkStatsSummary(NetworkStats summary) throws Exception {
        expect(mNetManager.getNetworkStatsSummary()).andReturn(summary).atLeastOnce();
    }

    private void expectNetworkStatsUidDetail(NetworkStats detail) throws Exception {
        expect(mNetManager.getNetworkStatsUidDetail(eq(UID_ALL))).andReturn(detail).atLeastOnce();
    }

    private void expectDefaultSettings() throws Exception {
        expectSettings(0L, HOUR_IN_MILLIS, WEEK_IN_MILLIS);
    }

    private void expectSettings(long persistThreshold, long bucketDuration, long maxHistory)
            throws Exception {
        expect(mSettings.getPollInterval()).andReturn(HOUR_IN_MILLIS).anyTimes();
        expect(mSettings.getPersistThreshold()).andReturn(persistThreshold).anyTimes();
        expect(mSettings.getNetworkBucketDuration()).andReturn(bucketDuration).anyTimes();
        expect(mSettings.getNetworkMaxHistory()).andReturn(maxHistory).anyTimes();
        expect(mSettings.getUidBucketDuration()).andReturn(bucketDuration).anyTimes();
        expect(mSettings.getUidMaxHistory()).andReturn(maxHistory).anyTimes();
        expect(mSettings.getTagMaxHistory()).andReturn(maxHistory).anyTimes();
        expect(mSettings.getTimeCacheMaxAge()).andReturn(DAY_IN_MILLIS).anyTimes();
    }

    private void expectTime(long currentTime) throws Exception {
        expect(mTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mTime.hasCache()).andReturn(true).anyTimes();
        expect(mTime.currentTimeMillis()).andReturn(currentTime).anyTimes();
        expect(mTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    private void performBootstrapPoll(long testStart, long elapsedRealtime) throws Exception {
        expectTime(testStart + elapsedRealtime);
        expectDefaultSettings();
        expectNetworkStatsSummary(buildEmptyStats(elapsedRealtime));
        expectNetworkStatsUidDetail(buildEmptyStats(elapsedRealtime));

        replay();
        mServiceContext.sendBroadcast(new Intent(ACTION_NETWORK_STATS_POLL));
        verifyAndReset();
    }

    private void assertStatsFilesExist(boolean exist) {
        final File networkFile = new File(mStatsDir, "netstats.bin");
        final File uidFile = new File(mStatsDir, "netstats_uid.bin");
        if (exist) {
            assertTrue(networkFile.exists());
            assertTrue(uidFile.exists());
        } else {
            assertFalse(networkFile.exists());
            assertFalse(uidFile.exists());
        }
    }

    private static void assertValues(NetworkStats stats, int i, String iface, int uid, int tag,
            long rxBytes, long rxPackets, long txBytes, long txPackets, int operations) {
        final NetworkStats.Entry entry = stats.getValues(i, null);
        assertEquals("unexpected iface", iface, entry.iface);
        assertEquals("unexpected uid", uid, entry.uid);
        assertEquals("unexpected tag", tag, entry.tag);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
    }

    private static void assertValues(NetworkStatsHistory stats, long start, long end, long rxBytes,
            long rxPackets, long txBytes, long txPackets, int operations) {
        final NetworkStatsHistory.Entry entry = stats.getValues(start, end, null);
        assertEquals("unexpected rxBytes", rxBytes, entry.rxBytes);
        assertEquals("unexpected rxPackets", rxPackets, entry.rxPackets);
        assertEquals("unexpected txBytes", txBytes, entry.txBytes);
        assertEquals("unexpected txPackets", txPackets, entry.txPackets);
        assertEquals("unexpected operations", operations, entry.operations);
    }

    private static NetworkState buildWifiState() {
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null);
    }

    private static NetworkState buildMobile3gState(String subscriberId) {
        final NetworkInfo info = new NetworkInfo(
                TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UMTS, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null, subscriberId);
    }

    private static NetworkState buildMobile4gState() {
        final NetworkInfo info = new NetworkInfo(TYPE_WIMAX, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null);
    }

    private static NetworkStats buildEmptyStats(long elapsedRealtime) {
        return new NetworkStats(elapsedRealtime, 0);
    }

    private void replay() {
        EasyMock.replay(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
    }

    private void verifyAndReset() {
        EasyMock.verify(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
        EasyMock.reset(mNetManager, mAlarmManager, mTime, mSettings, mConnManager);
    }
}
