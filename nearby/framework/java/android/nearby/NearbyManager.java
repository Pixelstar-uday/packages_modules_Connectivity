/*
 * Copyright 2021 The Android Open Source Project
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

package android.nearby;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.provider.Settings;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * This class provides a way to perform Nearby related operations such as scanning, broadcasting
 * and connecting to nearby devices.
 *
 * <p> To get a {@link NearbyManager} instance, call the
 * <code>Context.getSystemService(NearbyManager.class)</code>.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.NEARBY_SERVICE)
public class NearbyManager {

    /**
     * Whether allows Fast Pair to scan.
     *
     * (0 = disabled, 1 = enabled)
     *
     * @hide
     */
    public static final String FAST_PAIR_SCAN_ENABLED = "fast_pair_scan_enabled";

    @GuardedBy("sScanListeners")
    private static final WeakHashMap<ScanCallback, WeakReference<ScanListenerTransport>>
            sScanListeners = new WeakHashMap<>();
    @GuardedBy("sBroadcastListeners")
    private static final WeakHashMap<BroadcastCallback, WeakReference<BroadcastListenerTransport>>
            sBroadcastListeners = new WeakHashMap<>();

    private final INearbyManager mService;

    /**
     * Creates a new NearbyManager.
     *
     * @param service The service object.
     */
    NearbyManager(@NonNull INearbyManager service) {
        mService = service;
    }

    private static NearbyDevice toClientNearbyDevice(
            NearbyDeviceParcelable nearbyDeviceParcelable,
            @ScanRequest.ScanType int scanType) {
        if (scanType == ScanRequest.SCAN_TYPE_FAST_PAIR) {
            return new FastPairDevice.Builder()
                    .setName(nearbyDeviceParcelable.getName())
                    .addMedium(nearbyDeviceParcelable.getMedium())
                    .setRssi(nearbyDeviceParcelable.getRssi())
                    .setModelId(nearbyDeviceParcelable.getFastPairModelId())
                    .setBluetoothAddress(nearbyDeviceParcelable.getBluetoothAddress())
                    .setData(nearbyDeviceParcelable.getData()).build();
        }
        return null;
    }

    /**
     * Start scan for nearby devices with given parameters. Devices matching {@link ScanRequest}
     * will be delivered through the given callback.
     *
     * @param scanRequest Various parameters clients send when requesting scanning.
     * @param executor Executor where the listener method is called.
     * @param scanCallback The callback to notify clients when there is a scan result.
     */
    public void startScan(@NonNull ScanRequest scanRequest,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull ScanCallback scanCallback) {
        Objects.requireNonNull(scanRequest, "scanRequest must not be null");
        Objects.requireNonNull(scanCallback, "scanCallback must not be null");
        Objects.requireNonNull(executor, "executor must not be null");

        try {
            synchronized (sScanListeners) {
                WeakReference<ScanListenerTransport> reference = sScanListeners.get(scanCallback);
                ScanListenerTransport transport = reference != null ? reference.get() : null;
                if (transport == null) {
                    transport = new ScanListenerTransport(scanRequest.getScanType(), scanCallback,
                            executor);
                } else {
                    Preconditions.checkState(transport.isRegistered());
                    transport.setExecutor(executor);
                }
                mService.registerScanListener(scanRequest, transport);
                sScanListeners.put(scanCallback, new WeakReference<>(transport));
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the nearby device scan for the specified callback. The given callback
     * is guaranteed not to receive any invocations that happen after this method
     * is invoked.
     *
     * Suppressed lint: Registration methods should have overload that accepts delivery Executor.
     * Already have executor in startScan() method.
     *
     * @param scanCallback  The callback that was used to start the scan.
     */
    @SuppressLint("ExecutorRegistration")
    public void stopScan(@NonNull ScanCallback scanCallback) {
        Preconditions.checkArgument(scanCallback != null,
                "invalid null scanCallback");
        try {
            synchronized (sScanListeners) {
                WeakReference<ScanListenerTransport> reference = sScanListeners.remove(
                        scanCallback);
                ScanListenerTransport transport = reference != null ? reference.get() : null;
                if (transport != null) {
                    transport.unregister();
                    mService.unregisterScanListener(transport);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start broadcasting the request using nearby specification.
     *
     * @param broadcastRequest Request for the nearby broadcast.
     * @param executor Executor for running the callback.
     * @param callback Callback for notifying the client..
     */
    public void startBroadcast(@NonNull BroadcastRequest broadcastRequest,
            @CallbackExecutor @NonNull Executor executor, @NonNull BroadcastCallback callback) {
        try {
            synchronized (sBroadcastListeners) {
                WeakReference<BroadcastListenerTransport> reference = sBroadcastListeners.get(
                        callback);
                BroadcastListenerTransport transport = reference != null ? reference.get() : null;
                if (transport == null) {
                    transport = new BroadcastListenerTransport(callback, executor);
                } else {
                    Preconditions.checkState(transport.isRegistered());
                    transport.setExecutor(executor);
                }
                mService.startBroadcast(broadcastRequest, transport);
                sBroadcastListeners.put(callback, new WeakReference<>(transport));
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop the broadcast associated with the given callback.
     *
     * @param callback The callback that was used for starting the broadcast.
     */
    @SuppressLint("ExecutorRegistration")
    public void stopBroadcast(@NonNull BroadcastCallback callback) {
        try {
            synchronized (sBroadcastListeners) {
                WeakReference<BroadcastListenerTransport> reference = sBroadcastListeners.remove(
                        callback);
                BroadcastListenerTransport transport = reference != null ? reference.get() : null;
                if (transport != null) {
                    transport.unregister();
                    mService.stopBroadcast(transport);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Read from {@link Settings} whether Fast Pair scan is enabled.
     *
     * @param context the {@link Context} to query the setting.
     * @param def the default value if no setting value.
     * @return whether the Fast Pair is enabled.
     */
    public static boolean getFastPairScanEnabled(@NonNull Context context, boolean def) {
        final int enabled = Settings.Secure.getInt(
                context.getContentResolver(), FAST_PAIR_SCAN_ENABLED, (def ? 1 : 0));
        return enabled != 0;
    }

    /**
     * Write into {@link Settings} whether Fast Pair scan is enabled
     *
     * @param context the {@link Context} to set the setting.
     * @param enable whether the Fast Pair scan should be enabled.
     */
    public static void setFastPairScanEnabled(@NonNull Context context, boolean enable) {
        Settings.Secure.putInt(
                context.getContentResolver(), FAST_PAIR_SCAN_ENABLED, enable ? 1 : 0);
    }

    private static class ScanListenerTransport extends IScanListener.Stub {

        private @ScanRequest.ScanType int mScanType;
        private volatile @Nullable ScanCallback mScanCallback;
        private Executor mExecutor;

        ScanListenerTransport(@ScanRequest.ScanType int scanType, ScanCallback scanCallback,
                @CallbackExecutor Executor executor) {
            Preconditions.checkArgument(scanCallback != null,
                    "invalid null callback");
            Preconditions.checkState(ScanRequest.isValidScanType(scanType),
                    "invalid scan type : " + scanType
                            + ", scan type must be one of ScanRequest#SCAN_TYPE_");
            mScanType = scanType;
            mScanCallback = scanCallback;
            mExecutor = executor;
        }

        void setExecutor(Executor executor) {
            Preconditions.checkArgument(
                    executor != null, "invalid null executor");
            mExecutor = executor;
        }

        boolean isRegistered() {
            return mScanCallback != null;
        }

        void unregister() {
            mScanCallback = null;
        }

        @Override
        public void onDiscovered(NearbyDeviceParcelable nearbyDeviceParcelable)
                throws RemoteException {
            mExecutor.execute(() -> mScanCallback.onDiscovered(
                    toClientNearbyDevice(nearbyDeviceParcelable, mScanType)));
        }

        @Override
        public void onUpdated(NearbyDeviceParcelable nearbyDeviceParcelable)
                throws RemoteException {
            mExecutor.execute(
                    () -> mScanCallback.onUpdated(
                            toClientNearbyDevice(nearbyDeviceParcelable, mScanType)));
        }

        @Override
        public void onLost(NearbyDeviceParcelable nearbyDeviceParcelable) throws RemoteException {
            mExecutor.execute(
                    () -> mScanCallback.onLost(
                            toClientNearbyDevice(nearbyDeviceParcelable, mScanType)));
        }
    }

    private static class BroadcastListenerTransport extends IBroadcastListener.Stub {
        private volatile @Nullable BroadcastCallback mBroadcastCallback;
        private Executor mExecutor;

        BroadcastListenerTransport(BroadcastCallback broadcastCallback,
                @CallbackExecutor Executor executor) {
            mBroadcastCallback = broadcastCallback;
            mExecutor = executor;
        }

        void setExecutor(Executor executor) {
            Preconditions.checkArgument(
                    executor != null, "invalid null executor");
            mExecutor = executor;
        }

        boolean isRegistered() {
            return mBroadcastCallback != null;
        }

        void unregister() {
            mBroadcastCallback = null;
        }

        @Override
        public void onStatusChanged(int status) {
            mExecutor.execute(()-> mBroadcastCallback.onStatus(status));
        }
    }
}
