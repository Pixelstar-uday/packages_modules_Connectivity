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

package com.android.server;

import android.content.Context;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;

/**
 * Connectivity service initializer for core networking. This is called by system server to create
 * a new instance of ConnectivityService.
 */
public final class ConnectivityServiceInitializer extends SystemService {
    private static final String TAG = ConnectivityServiceInitializer.class.getSimpleName();
    private final ConnectivityService mConnectivity;
    private final IpSecService mIpSecService;
    private final NsdService mNsdService;

    public ConnectivityServiceInitializer(Context context) {
        super(context);
        // Load JNI libraries used by ConnectivityService and its dependencies
        System.loadLibrary("service-connectivity");
        mConnectivity = new ConnectivityService(context);
        mIpSecService = createIpSecService(context);
        mNsdService = createNsdService(context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.CONNECTIVITY_SERVICE);
        publishBinderService(Context.CONNECTIVITY_SERVICE, mConnectivity,
                /* allowIsolated= */ false);

        if (mIpSecService != null) {
            Log.i(TAG, "Registering " + Context.IPSEC_SERVICE);
            publishBinderService(Context.IPSEC_SERVICE, mIpSecService, /* allowIsolated= */ false);
        }

        if (mNsdService != null) {
            Log.i(TAG, "Registering " + Context.NSD_SERVICE);
            publishBinderService(Context.NSD_SERVICE, mNsdService, /* allowIsolated= */ false);
        }
    }

    /**
     * Return IpSecService instance, or null if current SDK is lower than T.
     */
    private IpSecService createIpSecService(final Context context) {
        if (!SdkLevel.isAtLeastT()) return null;

        return new IpSecService(context);
    }

    /** Return NsdService instance or null if current SDK is lower than T */
    private NsdService createNsdService(final Context context) {
        if (!SdkLevel.isAtLeastT()) return null;
        try {
            return NsdService.create(context);
        } catch (InterruptedException e) {
            Log.d(TAG, "Unable to get NSD service", e);
            return null;
        }
    }
}
