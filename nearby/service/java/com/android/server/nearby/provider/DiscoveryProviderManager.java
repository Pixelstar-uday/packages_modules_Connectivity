/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.nearby.NearbyService.TAG;

import android.content.Context;
import android.nearby.IScanListener;
import android.nearby.NearbyDeviceParcelable;
import android.nearby.ScanRequest;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.nearby.injector.Injector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manages all aspects of discovery providers.
 */
public class DiscoveryProviderManager implements AbstractDiscoveryProvider.Listener {

    protected final Object mLock = new Object();
    private final Context mContext;
    private final BleDiscoveryProvider mBleDiscoveryProvider;
    private @ScanRequest.ScanMode int mScanMode;

    @GuardedBy("mLock")
    private Map<IBinder, ScanListenerRecord> mScanTypeScanListenerRecordMap;

    @Override
    public void onNearbyDeviceDiscovered(NearbyDeviceParcelable nearbyDevice) {
        synchronized (mLock) {
            for (IBinder listenerBinder : mScanTypeScanListenerRecordMap.keySet()) {
                ScanListenerRecord record = mScanTypeScanListenerRecordMap.get(listenerBinder);
                if (record == null) {
                    Log.w(TAG, "DiscoveryProviderManager cannot find the scan record.");
                    continue;
                }
                try {
                    record.getScanListener().onDiscovered(
                            PrivacyFilter.filter(record.getScanRequest().getScanType(),
                                    nearbyDevice));
                } catch (RemoteException e) {
                    Log.w(TAG, "DiscoveryProviderManager failed to report onDiscovered.", e);
                }
            }
        }
    }

    public DiscoveryProviderManager(Context context, Injector injector) {
        mContext = context;
        mBleDiscoveryProvider = new BleDiscoveryProvider(mContext, injector);
        mScanTypeScanListenerRecordMap = new HashMap<>();
    }

    /**
     * Registers the listener in the manager and starts scan according to the requested scan mode.
     */
    public void registerScanListener(ScanRequest scanRequest, IScanListener listener) {
        synchronized (mLock) {
            IBinder listenerBinder = listener.asBinder();
            if (mScanTypeScanListenerRecordMap.containsKey(listener.asBinder())) {
                ScanRequest savedScanRequest = mScanTypeScanListenerRecordMap
                        .get(listenerBinder).getScanRequest();
                if (scanRequest.equals(savedScanRequest)) {
                    Log.d(TAG, "Already registered the scanRequest: " + scanRequest);
                    return;
                }
            }

            startProviders(scanRequest);

            mScanTypeScanListenerRecordMap.put(listenerBinder,
                    new ScanListenerRecord(scanRequest, listener));
            if (mScanMode < scanRequest.getScanMode()) {
                mScanMode = scanRequest.getScanMode();
                invalidateProviderScanMode();
            }
        }
    }

    /**
     * Unregisters the listener in the manager and adjusts the scan mode if necessary afterwards.
     */
    public void unregisterScanListener(IScanListener listener) {
        IBinder listenerBinder = listener.asBinder();
        synchronized (mLock) {
            if (!mScanTypeScanListenerRecordMap.containsKey(listenerBinder)) {
                Log.w(TAG,
                        "Cannot unregister the scanRequest because the request is never "
                                + "registered.");
                return;
            }

            ScanListenerRecord removedRecord = mScanTypeScanListenerRecordMap
                    .remove(listenerBinder);
            if (mScanTypeScanListenerRecordMap.isEmpty()) {
                stopProviders();
                return;
            }

            // Removes current highest scan mode requested and sets the next highest scan mode.
            if (removedRecord.getScanRequest().getScanMode() == mScanMode) {
                @ScanRequest.ScanMode int highestScanModeRequested = ScanRequest.SCAN_MODE_NO_POWER;
                // find the next highest scan mode;
                for (ScanListenerRecord record : mScanTypeScanListenerRecordMap.values()) {
                    @ScanRequest.ScanMode int scanMode = record.getScanRequest().getScanMode();
                    if (scanMode > highestScanModeRequested) {
                        highestScanModeRequested = scanMode;
                    }
                }
                if (mScanMode != highestScanModeRequested) {
                    mScanMode = highestScanModeRequested;
                    invalidateProviderScanMode();
                }
            }
        }
    }

    private void startProviders(ScanRequest scanRequest) {
        if (scanRequest.isEnableBle()) {
            startBleProvider(scanRequest);
        }
    }

    private void startBleProvider(ScanRequest scanRequest) {
        if (!mBleDiscoveryProvider.getController().isStarted()) {
            Log.d(TAG, "DiscoveryProviderManager starts Ble scanning.");
            mBleDiscoveryProvider.getController().start();
            mBleDiscoveryProvider.getController().setListener(this);
            mBleDiscoveryProvider.getController().setProviderScanMode(scanRequest.getScanMode());
        }
    }

    private void stopProviders() {
        stopBleProvider();
    }

    private void stopBleProvider() {
        mBleDiscoveryProvider.getController().stop();
    }

    private void invalidateProviderScanMode() {
        if (!mBleDiscoveryProvider.getController().isStarted()) {
            Log.d(TAG,
                    "Skip invalidating BleDiscoveryProvider scan mode because the provider not "
                            + "started.");
            return;
        }
        mBleDiscoveryProvider.getController().setProviderScanMode(mScanMode);
    }

    private static class ScanListenerRecord {

        private final ScanRequest mScanRequest;

        private final IScanListener mScanListener;


        ScanListenerRecord(ScanRequest scanRequest, IScanListener iScanListener) {
            mScanListener = iScanListener;
            mScanRequest = scanRequest;
        }

        IScanListener getScanListener() {
            return mScanListener;
        }

        ScanRequest getScanRequest() {
            return mScanRequest;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof ScanListenerRecord) {
                ScanListenerRecord otherScanListenerRecord = (ScanListenerRecord) other;
                return Objects.equals(mScanRequest, otherScanListenerRecord.mScanRequest)
                        && Objects.equals(mScanListener, otherScanListenerRecord.mScanListener);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return  Objects.hash(mScanListener, mScanRequest);
        }
    }
}
