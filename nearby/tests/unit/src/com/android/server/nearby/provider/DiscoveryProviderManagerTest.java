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

package com.android.server.nearby.provider;

import static android.nearby.PresenceCredential.IDENTITY_TYPE_PRIVATE;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_PRESENCE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.nearby.DataElement;
import android.nearby.IScanListener;
import android.nearby.NearbyDeviceParcelable;
import android.nearby.PresenceScanFilter;
import android.nearby.PublicCredential;
import android.nearby.ScanRequest;
import android.os.IBinder;

import com.android.server.nearby.injector.Injector;
import com.android.server.nearby.util.identity.CallerIdentity;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

public class DiscoveryProviderManagerTest {
    private static final int SCAN_MODE_CHRE_ONLY = 3;
    private static final int DATA_TYPE_SCAN_MODE = 102;

    @Mock Injector mInjector;
    @Mock Context mContext;
    @Mock AppOpsManager mAppOpsManager;
    @Mock BleDiscoveryProvider mBleDiscoveryProvider;
    @Mock ChreDiscoveryProvider mChreDiscoveryProvider;
    @Mock DiscoveryProviderController mBluetoothController;
    @Mock DiscoveryProviderController mChreController;
    @Mock IScanListener mScanListener;
    @Mock CallerIdentity mCallerIdentity;
    @Mock DiscoveryProviderManager.ScanListenerDeathRecipient mScanListenerDeathRecipient;
    @Mock IBinder mIBinder;

    private DiscoveryProviderManager mDiscoveryProviderManager;
    private Map<IBinder, DiscoveryProviderManager.ScanListenerRecord>
            mScanTypeScanListenerRecordMap;

    private static final int RSSI = -60;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mInjector.getAppOpsManager()).thenReturn(mAppOpsManager);
        when(mBleDiscoveryProvider.getController()).thenReturn(mBluetoothController);
        when(mChreDiscoveryProvider.getController()).thenReturn(mChreController);

        mScanTypeScanListenerRecordMap = new HashMap<>();
        mDiscoveryProviderManager =
                new DiscoveryProviderManager(mContext, mInjector, mBleDiscoveryProvider,
                        mChreDiscoveryProvider,
                        mScanTypeScanListenerRecordMap);
    }

    @Test
    public void testOnNearbyDeviceDiscovered() {
        NearbyDeviceParcelable nearbyDeviceParcelable = new NearbyDeviceParcelable.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .build();
        mDiscoveryProviderManager.onNearbyDeviceDiscovered(nearbyDeviceParcelable);
    }

    @Test
    public void testInvalidateProviderScanMode() {
        mDiscoveryProviderManager.invalidateProviderScanMode();
    }

    @Test
    public void testStartProviders_chreOnlyChreAvailable_bleProviderNotStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(true);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getChreOnlyPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, never()).start();
        assertThat(start).isTrue();
    }

    @Test
    public void testStartProviders_chreOnlyChreAvailable_multipleFilters_bleProviderNotStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(true);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getChreOnlyPresenceScanFilter())
                .addScanFilter(getPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, never()).start();
        assertThat(start).isTrue();
    }

    @Test
    public void testStartProviders_chreOnlyChreUnavailable_bleProviderNotStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(false);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getChreOnlyPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, never()).start();
        assertThat(start).isFalse();
    }

    @Test
    public void testStartProviders_notChreOnlyChreAvailable_bleProviderNotStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(true);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, never()).start();
        assertThat(start).isTrue();
    }

    @Test
    public void testStartProviders_notChreOnlyChreUnavailable_bleProviderStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(false);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, atLeastOnce()).start();
        assertThat(start).isTrue();
    }

    @Test
    public void testStartProviders_chreOnlyChreUndetermined_bleProviderNotStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(null);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getChreOnlyPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, never()).start();
        assertThat(start).isNull();
    }

    @Test
    public void testStartProviders_notChreOnlyChreUndetermined_bleProviderStarted() {
        when(mChreDiscoveryProvider.available()).thenReturn(null);

        ScanRequest scanRequest = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .addScanFilter(getPresenceScanFilter()).build();
        DiscoveryProviderManager.ScanListenerRecord record =
                new DiscoveryProviderManager.ScanListenerRecord(scanRequest, mScanListener,
                        mCallerIdentity, mScanListenerDeathRecipient);
        mScanTypeScanListenerRecordMap.put(mIBinder, record);

        Boolean start = mDiscoveryProviderManager.startProviders(scanRequest);
        verify(mBluetoothController, atLeastOnce()).start();
        assertThat(start).isTrue();
    }

    private static PresenceScanFilter getPresenceScanFilter() {
        final byte[] secretId = new byte[]{1, 2, 3, 4};
        final byte[] authenticityKey = new byte[]{0, 1, 1, 1};
        final byte[] publicKey = new byte[]{1, 1, 2, 2};
        final byte[] encryptedMetadata = new byte[]{1, 2, 3, 4, 5};
        final byte[] metadataEncryptionKeyTag = new byte[]{1, 1, 3, 4, 5};

        PublicCredential credential = new PublicCredential.Builder(
                secretId, authenticityKey, publicKey, encryptedMetadata, metadataEncryptionKeyTag)
                .setIdentityType(IDENTITY_TYPE_PRIVATE)
                .build();

        final int action = 123;
        return new PresenceScanFilter.Builder()
                .addCredential(credential)
                .setMaxPathLoss(RSSI)
                .addPresenceAction(action)
                .build();
    }

    private static PresenceScanFilter getChreOnlyPresenceScanFilter() {
        final byte[] secretId = new byte[]{1, 2, 3, 4};
        final byte[] authenticityKey = new byte[]{0, 1, 1, 1};
        final byte[] publicKey = new byte[]{1, 1, 2, 2};
        final byte[] encryptedMetadata = new byte[]{1, 2, 3, 4, 5};
        final byte[] metadataEncryptionKeyTag = new byte[]{1, 1, 3, 4, 5};

        PublicCredential credential = new PublicCredential.Builder(
                secretId, authenticityKey, publicKey, encryptedMetadata, metadataEncryptionKeyTag)
                .setIdentityType(IDENTITY_TYPE_PRIVATE)
                .build();

        final int action = 123;
        DataElement scanModeElement = new DataElement(DATA_TYPE_SCAN_MODE,
                new byte[]{SCAN_MODE_CHRE_ONLY});
        return new PresenceScanFilter.Builder()
                .addCredential(credential)
                .setMaxPathLoss(RSSI)
                .addPresenceAction(action)
                .addExtendedProperty(scanModeElement)
                .build();
    }
}
