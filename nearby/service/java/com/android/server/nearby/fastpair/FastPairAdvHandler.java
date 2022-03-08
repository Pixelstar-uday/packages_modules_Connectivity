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

package com.android.server.nearby.fastpair;

import static com.android.server.nearby.fastpair.Constant.TAG;

import static com.google.common.primitives.Bytes.concat;

import android.accounts.Account;
import android.annotation.Nullable;
import android.content.Context;
import android.nearby.FastPairDevice;
import android.nearby.NearbyDevice;
import android.util.Log;

import com.android.server.nearby.common.ble.decode.FastPairDecoder;
import com.android.server.nearby.common.bloomfilter.BloomFilter;
import com.android.server.nearby.common.bloomfilter.FastPairBloomFilterHasher;
import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;
import com.android.server.nearby.provider.FastPairDataProvider;
import com.android.server.nearby.util.DataUtils;
import com.android.server.nearby.util.Hex;

import java.util.List;

import service.proto.Cache;
import service.proto.Data;
import service.proto.Rpcs;

/**
 * Handler that handle fast pair related broadcast.
 */
public class FastPairAdvHandler {
    Context mContext;
    String mBleAddress;

    /** The types about how the bloomfilter is processed. */
    public enum ProcessBloomFilterType {
        IGNORE, // The bloomfilter is not handled. e.g. distance is too far away.
        CACHE, // The bloomfilter is recognized in the local cache.
        FOOTPRINT, // Need to check the bloomfilter from the footprints.
        ACCOUNT_KEY_HIT // The specified account key was hit the bloom filter.
    }

    /**
     * Constructor function.
     */
    public FastPairAdvHandler(Context context) {
        mContext = context;
    }

    /**
     * Handles all of the scanner result. Fast Pair will handle model id broadcast bloomfilter
     * broadcast and battery level broadcast.
     */
    public void handleBroadcast(NearbyDevice device) {
        FastPairDevice fastPairDevice = (FastPairDevice) device;
        mBleAddress = fastPairDevice.getBluetoothAddress();
        FastPairDataProvider dataProvider = FastPairDataProvider.getInstance();
        if (dataProvider == null) {
            return;
        }
        List<Account> accountList = dataProvider.loadFastPairEligibleAccounts();
        if (FastPairDecoder.checkModelId(fastPairDevice.getData())) {
            byte[] model = FastPairDecoder.getModelId(fastPairDevice.getData());
            Log.d(TAG, "On discovery model id " + Hex.bytesToStringLowercase(model));
            // Use api to get anti spoofing key from model id.
            try {
                Rpcs.GetObservedDeviceResponse response =
                        dataProvider.loadFastPairAntispoofkeyDeviceMetadata(model);
                if (response == null) {
                    Log.e(TAG, "server does not have model id "
                            + Hex.bytesToStringLowercase(model));
                    return;
                }
                Locator.get(mContext, FastPairHalfSheetManager.class).showHalfSheet(
                        DataUtils.toScanFastPairStoreItem(
                                response, mBleAddress,
                                accountList.isEmpty() ? null : accountList.get(0).name));
            } catch (IllegalStateException e) {
                Log.e(TAG, "OEM does not construct fast pair data proxy correctly");
            }
        } else {
            // Start to process bloom filter
            try {
                byte[] bloomFilterByteArray = FastPairDecoder
                        .getBloomFilter(fastPairDevice.getData());
                byte[] bloomFilterSalt = FastPairDecoder
                        .getBloomFilterSalt(fastPairDevice.getData());
                if (bloomFilterByteArray == null || bloomFilterByteArray.length == 0) {
                    return;
                }
                for (Account account : accountList) {
                    List<Data.FastPairDeviceWithAccountKey> listDevices =
                            dataProvider.loadFastPairDeviceWithAccountKey(account);
                    Data.FastPairDeviceWithAccountKey recognizedDevice =
                            findRecognizedDevice(listDevices,
                                    new BloomFilter(bloomFilterByteArray,
                                            new FastPairBloomFilterHasher()), bloomFilterSalt);

                    if (recognizedDevice != null) {
                        Log.d(TAG, "find matched device show notification to remind"
                                + " user to pair");
                        // Check if the device is already paired
                        List<Cache.StoredFastPairItem> storedFastPairItemList =
                                Locator.get(mContext, FastPairCacheManager.class)
                                        .getAllSavedStoredFastPairItem();
                        Cache.StoredFastPairItem recognizedStoredFastPairItem =
                                findRecognizedDeviceFromCachedItem(storedFastPairItemList,
                                        new BloomFilter(bloomFilterByteArray,
                                                new FastPairBloomFilterHasher()), bloomFilterSalt);
                        if (recognizedStoredFastPairItem != null) {
                            // The bloomfilter is recognized in the cache so the device is paired
                            // before
                            Log.d(TAG, "bloom filter is recognized in the cache");
                            continue;
                        } else {
                            Log.d(TAG, "bloom filter is not recognized not paired before");
                        }

                        return;
                    }
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "OEM does not construct fast pair data proxy correctly");
            }

        }
    }

    /**
     * Checks the bloom filter to see if any of the devices are recognized and should have a
     * notification displayed for them. A device is recognized if the account key + salt combination
     * is inside the bloom filter.
     */
    @Nullable
    static Data.FastPairDeviceWithAccountKey findRecognizedDevice(
            List<Data.FastPairDeviceWithAccountKey> devices, BloomFilter bloomFilter, byte[] salt) {
        Log.d(TAG, "saved devices size in the account is " + devices.size());
        for (Data.FastPairDeviceWithAccountKey device : devices) {
            byte[] rotatedKey = concat(device.getAccountKey().toByteArray(), salt);
            StringBuilder sb = new StringBuilder();
            for (byte b : rotatedKey) {
                sb.append(b);
            }
            if (bloomFilter.possiblyContains(rotatedKey)) {
                Log.d(TAG, "match " + sb.toString());
                return device;
            } else {
                Log.d(TAG, "not match " + sb.toString());
            }
        }
        return null;
    }

    @Nullable
    static Cache.StoredFastPairItem findRecognizedDeviceFromCachedItem(
            List<Cache.StoredFastPairItem> devices, BloomFilter bloomFilter, byte[] salt) {
        for (Cache.StoredFastPairItem device : devices) {
            byte[] rotatedKey = concat(device.getAccountKey().toByteArray(), salt);
            if (bloomFilter.possiblyContains(rotatedKey)) {
                return device;
            }
        }
        return null;
    }

}
