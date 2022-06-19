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

package com.android.server.nearby.fastpair.pairinghandler;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import androidx.annotation.Nullable;

import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.LocatorContextWrapper;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.fastpair.notification.FastPairNotificationManager;
import com.android.server.nearby.fastpair.testing.FakeDiscoveryItems;

import com.google.protobuf.ByteString;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

import service.proto.Cache;
import service.proto.Rpcs;

public class NotificationPairingProgressHandlerTest {
    @Mock
    Locator mLocator;
    @Mock
    LocatorContextWrapper mContextWrapper;
    @Mock
    Clock mClock;
    @Mock
    FastPairCacheManager mFastPairCacheManager;

    private static final byte[] ACCOUNT_KEY = new byte[]{0x01, 0x02};
    private static final int SUBSEQUENT_PAIR_START = 1310;
    private static final int SUBSEQUENT_PAIR_END = 1320;
    private static DiscoveryItem sDiscoveryItem;
    private static  NotificationPairingProgressHandler sNotificationPairingProgressHandler;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContextWrapper.getLocator()).thenReturn(mLocator);
        mLocator.overrideBindingForTest(FastPairCacheManager.class,
                mFastPairCacheManager);
        mLocator.overrideBindingForTest(Clock.class, mClock);
        sDiscoveryItem = FakeDiscoveryItems.newFastPairDiscoveryItem(mContextWrapper);
        sDiscoveryItem.setStoredItemForTest(
                sDiscoveryItem.getStoredItemForTest().toBuilder()
                        .setAuthenticationPublicKeySecp256R1(ByteString.copyFrom(ACCOUNT_KEY))
                        .setFastPairInformation(
                                Cache.FastPairInformation.newBuilder()
                                        .setDeviceType(Rpcs.DeviceType.HEADPHONES).build())
                        .build());
        sNotificationPairingProgressHandler = createProgressHandler(ACCOUNT_KEY, sDiscoveryItem);
    }

    @Test
    public void getPairEndEventCode() {
        assertThat(sNotificationPairingProgressHandler
                .getPairEndEventCode()).isEqualTo(SUBSEQUENT_PAIR_END);
    }

    @Test
    public void getPairStartEventCode() {
        assertThat(sNotificationPairingProgressHandler
                .getPairStartEventCode()).isEqualTo(SUBSEQUENT_PAIR_START);
    }

    @Test
    public void onReadyToPair() {
        sNotificationPairingProgressHandler.onReadyToPair();
    }

    @Test
    public void  onPairingFailed() {
        Throwable e = new Throwable("Pairing Failed");
        sNotificationPairingProgressHandler.onPairingFailed(e);
    }

    @Test
    public void onPairingSuccess() {
        sNotificationPairingProgressHandler.onPairingSuccess(sDiscoveryItem.getMacAddress());
    }

    private NotificationPairingProgressHandler createProgressHandler(
            @Nullable byte[] accountKey, DiscoveryItem fastPairItem) {
        FastPairNotificationManager fastPairNotificationManager =
                new FastPairNotificationManager(mContextWrapper, fastPairItem, true);
        FastPairHalfSheetManager fastPairHalfSheetManager =
                new FastPairHalfSheetManager(mContextWrapper);
        mLocator.overrideBindingForTest(FastPairHalfSheetManager.class, fastPairHalfSheetManager);
        NotificationPairingProgressHandler mNotificationPairingProgressHandler =
                new NotificationPairingProgressHandler(
                        mContextWrapper,
                        fastPairItem,
                        fastPairItem.getAppPackageName(),
                        accountKey,
                        fastPairNotificationManager);
        return mNotificationPairingProgressHandler;
    }
}
