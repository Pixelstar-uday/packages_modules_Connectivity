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

package com.android.server.nearby.fastpair.halfsheet;

import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.ACTIVE;
import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.DISMISSED;
import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN;
import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN_LONG;
import static com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager.DISMISS_HALFSHEET_RUNNABLE_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.nearby.FastPairStatusCallback;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.common.eventloop.NamedRunnable;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.FastPairController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import service.proto.Cache;

public class FastPairHalfSheetManagerTest {
    private static final String MODEL_ID = "model_id";
    private static final String BLEADDRESS = "11:22:44:66";
    private static final String NAME = "device_name";
    private static final int PASSKEY = 1234;
    private FastPairHalfSheetManager mFastPairHalfSheetManager;
    private Cache.ScanFastPairStoreItem mScanFastPairStoreItem;
    private ResolveInfo mResolveInfo;
    private List<ResolveInfo> mResolveInfoList;
    private ApplicationInfo mApplicationInfo;
    @Mock private Context mContext;
    @Mock
    LocatorContextWrapper mContextWrapper;
    @Mock
    PackageManager mPackageManager;
    @Mock
    Locator mLocator;
    @Mock
    FastPairController mFastPairController;
    @Mock
    EventLoop mEventLoop;
    @Mock
    FastPairStatusCallback mFastPairStatusCallback;
    @Mock
    FastPairUiServiceImpl mFastPairUiService;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLocator.overrideBindingForTest(FastPairController.class, mFastPairController);
        mLocator.overrideBindingForTest(EventLoop.class, mEventLoop);

        mResolveInfo = new ResolveInfo();
        mResolveInfoList = new ArrayList<>();
        mResolveInfo.activityInfo = new ActivityInfo();
        mApplicationInfo = new ApplicationInfo();
        mPackageManager = mock(PackageManager.class);

        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver());
        when(mContextWrapper.getPackageManager()).thenReturn(mPackageManager);
        when(mContextWrapper.getLocator()).thenReturn(mLocator);
        when(mLocator.get(EventLoop.class)).thenReturn(mEventLoop);
        when(mPackageManager.queryIntentActivities(any(), anyInt())).thenReturn(mResolveInfoList);
        when(mPackageManager.canRequestPackageInstalls()).thenReturn(false);

        mScanFastPairStoreItem = Cache.ScanFastPairStoreItem.newBuilder()
                .setModelId(MODEL_ID)
                .setAddress(BLEADDRESS)
                .setDeviceName(NAME)
                .build();
    }

    @Test
    public void verifyFastPairHalfSheetManagerBehavior() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        ArgumentCaptor<Intent> intentArgumentCaptor = ArgumentCaptor.forClass(Intent.class);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        verify(mContextWrapper, atLeastOnce())
                .startActivityAsUser(intentArgumentCaptor.capture(), eq(UserHandle.CURRENT));
    }

    @Test
    public void verifyFastPairHalfSheetManagerHalfSheetApkNotValidBehavior() {
        // application directory is wrong
        mApplicationInfo.sourceDir = "/apex/com.android.nearby";
        mApplicationInfo.packageName = "test.package";
        mResolveInfo.activityInfo.applicationInfo = mApplicationInfo;
        mResolveInfoList.add(mResolveInfo);
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        ArgumentCaptor<Intent> intentArgumentCaptor = ArgumentCaptor.forClass(Intent.class);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        verify(mContextWrapper, never())
                .startActivityAsUser(intentArgumentCaptor.capture(), eq(UserHandle.CURRENT));
    }

    @Test
    public void testHalfSheetForegroundState() {
        configResolveInfoList();
        mFastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);
        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);
        assertThat(mFastPairHalfSheetManager.getHalfSheetForeground()).isTrue();
        mFastPairHalfSheetManager.dismiss(MODEL_ID);
        assertThat(mFastPairHalfSheetManager.getHalfSheetForeground()).isFalse();
    }

    @Test
    public void testEmptyMethods() {
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        mFastPairHalfSheetManager.destroyBluetoothPairController();
        mFastPairHalfSheetManager.notifyPairingProcessDone(true, BLEADDRESS, null);
        mFastPairHalfSheetManager.showPairingFailed();
        mFastPairHalfSheetManager.showPairingHalfSheet(null);
        mFastPairHalfSheetManager.showPairingSuccessHalfSheet(BLEADDRESS);
        mFastPairHalfSheetManager.showPasskeyConfirmation(null, PASSKEY);
    }

    @Test
    public void showInitialPairingHalfSheetThenDismissOnce_stateDISMISSED() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        FastPairHalfSheetBlocklist mHalfSheetBlocklist =
                mFastPairHalfSheetManager.getHalfSheetBlocklist();

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);
        mFastPairHalfSheetManager.dismiss(MODEL_ID);

        Integer halfSheetId = mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID);

        //First time dismiss -> state: DISMISSED
        assertThat(mHalfSheetBlocklist.get(halfSheetId).getState()).isEqualTo(DISMISSED);
    }

    @Test
    public void showInitialPairingHalfSheetThenBan_stateDO_NOT_SHOW_AGAIN() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        FastPairHalfSheetBlocklist mHalfSheetBlocklist =
                mFastPairHalfSheetManager.getHalfSheetBlocklist();

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);
        mFastPairHalfSheetManager.dismiss(MODEL_ID);
        mFastPairHalfSheetManager.dismiss(MODEL_ID);

        Integer halfSheetId = mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID);

        //First time ban -> state: DO_NOT_SHOW_AGAIN
        assertThat(mHalfSheetBlocklist.get(halfSheetId).getState()).isEqualTo(DO_NOT_SHOW_AGAIN);
    }

    @Test
    public void showInitialPairingHalfSheetThenBanTwice_stateDO_NOT_SHOW_AGAIN_LONG() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        FastPairHalfSheetBlocklist mHalfSheetBlocklist =
                mFastPairHalfSheetManager.getHalfSheetBlocklist();

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);
        mFastPairHalfSheetManager.dismiss(MODEL_ID);
        mFastPairHalfSheetManager.dismiss(MODEL_ID);
        mFastPairHalfSheetManager.dismiss(MODEL_ID);

        Integer halfSheetId = mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID);

        //Second time ban -> state: DO_NOT_SHOW_AGAIN
        assertThat(mHalfSheetBlocklist.get(halfSheetId).getState())
                .isEqualTo(DO_NOT_SHOW_AGAIN_LONG);
    }

    @Test
    public void testResetBanSate_resetDISMISSEDtoACTIVE() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        FastPairHalfSheetBlocklist mHalfSheetBlocklist =
                mFastPairHalfSheetManager.getHalfSheetBlocklist();

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        Integer halfSheetId = mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID);

        mHalfSheetBlocklist.updateState(halfSheetId, DISMISSED);
        mFastPairHalfSheetManager.resetBanState(MODEL_ID);

        assertThat(mHalfSheetBlocklist.get(halfSheetId).getState()).isEqualTo(ACTIVE);
    }

    @Test
    public void testResetBanSate_resetDO_NOT_SHOW_AGAINtoACTIVE() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        FastPairHalfSheetBlocklist mHalfSheetBlocklist =
                mFastPairHalfSheetManager.getHalfSheetBlocklist();

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        Integer halfSheetId = mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID);

        mHalfSheetBlocklist.updateState(halfSheetId, DO_NOT_SHOW_AGAIN);
        mFastPairHalfSheetManager.resetBanState(MODEL_ID);

        assertThat(mHalfSheetBlocklist.get(halfSheetId).getState()).isEqualTo(ACTIVE);
    }

    @Test
    public void testResetBanSate_resetDO_NOT_SHOW_AGAIN_LONGtoACTIVE() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);
        FastPairHalfSheetBlocklist mHalfSheetBlocklist =
                mFastPairHalfSheetManager.getHalfSheetBlocklist();

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        Integer halfSheetId = mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID);

        mHalfSheetBlocklist.updateState(halfSheetId, DO_NOT_SHOW_AGAIN_LONG
        );
        mFastPairHalfSheetManager.resetBanState(MODEL_ID);

        assertThat(mHalfSheetBlocklist.get(halfSheetId).getState()).isEqualTo(ACTIVE);
    }

    @Test
    public void testReportDonePairing() {
        configResolveInfoList();
        mFastPairHalfSheetManager = new FastPairHalfSheetManager(mContextWrapper);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        assertThat(mFastPairHalfSheetManager.getHalfSheetBlocklist().size()).isEqualTo(1);

        mFastPairHalfSheetManager
                .reportDonePairing(mFastPairHalfSheetManager.mModelIdMap.get(MODEL_ID));

        assertThat(mFastPairHalfSheetManager.getHalfSheetBlocklist().size()).isEqualTo(0);
    }

    @Test
    public void showInitialPairingHalfSheet_AutoDismiss() throws InterruptedException {
        configResolveInfoList();
        mFastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        verifyInitialPairingNameRunnablePostedTimes(1);
    }

    @Test
    public void showInitialPairingHalfSheet_whenUiShownAndItemWithTheSameAddress() {
        Cache.ScanFastPairStoreItem testItem = Cache.ScanFastPairStoreItem.newBuilder()
                .setModelId(MODEL_ID)
                .setAddress(BLEADDRESS)
                .setDeviceName(NAME)
                .build();
        configResolveInfoList();
        mFastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        verifyHalfSheetActivityIntent(1);
        verifyInitialPairingNameRunnablePostedTimes(1);

        mFastPairHalfSheetManager.showHalfSheet(testItem);
        // When half sheet shown and receive broadcast from a same address,
        // DO NOT request start-activity to avoid unnecessary memory usage,
        // Just reset the auto dismiss timeout for the new request
        verifyHalfSheetActivityIntent(1);
        verifyInitialPairingNameRunnablePostedTimes(2);
    }

    @Test
    public void showInitialPairingHalfSheet_whenUiShowAndItemWithDifferentAddressSameModelId() {
        Cache.ScanFastPairStoreItem testItem = Cache.ScanFastPairStoreItem.newBuilder()
                .setModelId(MODEL_ID)
                .setAddress("99:99:99:99:99:99")
                .setDeviceName(NAME)
                .build();
        configResolveInfoList();
        mFastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        verifyHalfSheetActivityIntent(1);
        verifyInitialPairingNameRunnablePostedTimes(1);

        mFastPairHalfSheetManager.showHalfSheet(testItem);
        // When half sheet shown and receive broadcast from a same model id with different address,
        // DO NOT rest the auto dismiss timeout. No action is required.
        verifyHalfSheetActivityIntent(1);
        verifyInitialPairingNameRunnablePostedTimes(1);
    }

    @Test
    public void showInitialPairingHalfSheet_whenUiShowAndItemWithDifferentModelId() {
        Cache.ScanFastPairStoreItem testItem = Cache.ScanFastPairStoreItem.newBuilder()
                .setModelId("0000C")
                .setAddress("99:99:99:99:99:99")
                .setDeviceName(NAME)
                .build();
        configResolveInfoList();
        mFastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);

        mFastPairHalfSheetManager.showHalfSheet(mScanFastPairStoreItem);

        verifyInitialPairingNameRunnablePostedTimes(1);
        verifyHalfSheetActivityIntent(1);

        mFastPairHalfSheetManager.showHalfSheet(testItem);
        // When half sheet shown and receive broadcast from a different model id,
        // the new request should be ignored. No action is required.
        verifyHalfSheetActivityIntent(1);
        verifyInitialPairingNameRunnablePostedTimes(1);
    }

    @Test
    public void testReportActivelyPairing() {
        configResolveInfoList();
        mFastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);

        assertThat(mFastPairHalfSheetManager.isActivePairing()).isFalse();

        mFastPairHalfSheetManager.reportActivelyPairing();

        assertThat(mFastPairHalfSheetManager.isActivePairing()).isTrue();
    }

    private void verifyInitialPairingNameRunnablePostedTimes(int times) {
        ArgumentCaptor<NamedRunnable> captor = ArgumentCaptor.forClass(NamedRunnable.class);

        verify(mEventLoop, times(times)).postRunnableDelayed(captor.capture(), anyLong());
        assertThat(
                captor.getAllValues().stream()
                        .filter(r -> r.name.equals(DISMISS_HALFSHEET_RUNNABLE_NAME))
                        .count())
                .isEqualTo(times);
    }

    private void verifyHalfSheetActivityIntent(int times) {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);

        verify(mContextWrapper, times(times))
                .startActivityAsUser(captor.capture(), eq(UserHandle.CURRENT));
        assertThat(
                captor.getAllValues().stream()
                        .filter(r -> r.getAction().equals("android.nearby.SHOW_HALFSHEET"))
                        .count())
                .isEqualTo(times);
    }

    private void configResolveInfoList() {
        mApplicationInfo.sourceDir = "/apex/com.android.tethering";
        mApplicationInfo.packageName = "test.package";
        mResolveInfo.activityInfo.applicationInfo = mApplicationInfo;
        mResolveInfoList.add(mResolveInfo);
    }
}
