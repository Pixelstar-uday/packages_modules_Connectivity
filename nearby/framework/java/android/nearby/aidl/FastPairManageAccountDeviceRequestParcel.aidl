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

package android.nearby.aidl;

import android.accounts.Account;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;

/**
 * Request details for managing Fast Pair device-account mapping.
 * {@hide}
 */
 // TODO(b/204780849): remove unnecessary fields and polish comments.
parcelable FastPairManageAccountDeviceRequestParcel {
    Account account;
    // MANAGE_ACCOUNT_DEVICE_ADD: add Fast Pair device to the account.
    // MANAGE_ACCOUNT_DEVICE_REMOVE: remove Fast Pair device from the account.
    int requestType;
    // Fast Pair account key-ed device metadata.
    FastPairAccountKeyDeviceMetadataParcel accountKeyDeviceMetadata;
    // BLE address of the device at the device add time.
    String bleAddress;
}