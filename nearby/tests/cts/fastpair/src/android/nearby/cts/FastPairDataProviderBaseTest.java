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

package android.nearby.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.accounts.Account;
import android.nearby.FastPairAccountKeyDeviceMetadata;
import android.nearby.FastPairAntispoofkeyDeviceMetadata;
import android.nearby.FastPairDataProviderBase;
import android.nearby.FastPairDeviceMetadata;
import android.nearby.FastPairDiscoveryItem;
import android.nearby.FastPairEligibleAccount;
import android.nearby.aidl.FastPairAccountDevicesMetadataRequestParcel;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataRequestParcel;
import android.nearby.aidl.FastPairDeviceMetadataParcel;
import android.nearby.aidl.FastPairDiscoveryItemParcel;
import android.nearby.aidl.FastPairEligibleAccountParcel;
import android.nearby.aidl.FastPairEligibleAccountsRequestParcel;
import android.nearby.aidl.FastPairManageAccountDeviceRequestParcel;
import android.nearby.aidl.FastPairManageAccountRequestParcel;
import android.nearby.aidl.IFastPairAccountDevicesMetadataCallback;
import android.nearby.aidl.IFastPairAntispoofkeyDeviceMetadataCallback;
import android.nearby.aidl.IFastPairDataProvider;
import android.nearby.aidl.IFastPairEligibleAccountsCallback;
import android.nearby.aidl.IFastPairManageAccountCallback;
import android.nearby.aidl.IFastPairManageAccountDeviceCallback;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class FastPairDataProviderBaseTest {

    private static final String TAG = "FastPairDataProviderBaseTest";

    private static final String ASSISTANT_SETUP_HALFSHEET = "ASSISTANT_SETUP_HALFSHEET";
    private static final String ASSISTANT_SETUP_NOTIFICATION = "ASSISTANT_SETUP_NOTIFICATION";
    private static final int BLE_TX_POWER  = 5;
    private static final String CONFIRM_PIN_DESCRIPTION = "CONFIRM_PIN_DESCRIPTION";
    private static final String CONFIRM_PIN_TITLE = "CONFIRM_PIN_TITLE";
    private static final String CONNECT_SUCCESS_COMPANION_APP_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_INSTALLED";
    private static final String CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED";
    private static final float DELTA = 0.001f;
    private static final int DEVICE_TYPE = 7;
    private static final String DOWNLOAD_COMPANION_APP_DESCRIPTION =
            "DOWNLOAD_COMPANION_APP_DESCRIPTION";
    private static final Account ELIGIBLE_ACCOUNT_1 = new Account("abc@google.com", "type1");
    private static final boolean ELIGIBLE_ACCOUNT_1_OPT_IN = true;
    private static final Account ELIGIBLE_ACCOUNT_2 = new Account("def@gmail.com", "type2");
    private static final boolean ELIGIBLE_ACCOUNT_2_OPT_IN = false;
    private static final Account MANAGE_ACCOUNT = new Account("ghi@gmail.com", "type3");
    private static final Account ACCOUNTDEVICES_METADATA_ACCOUNT =
            new Account("jk@gmail.com", "type4");

    private static final int ERROR_CODE_BAD_REQUEST =
            FastPairDataProviderBase.ERROR_CODE_BAD_REQUEST;
    private static final int MANAGE_ACCOUNT_REQUEST_TYPE =
            FastPairDataProviderBase.MANAGE_REQUEST_ADD;
    private static final String ERROR_STRING = "ERROR_STRING";
    private static final String FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION =
            "FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION";
    private static final String FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION =
            "FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION";
    private static final byte[] IMAGE = new byte[] {7, 9};
    private static final String IMAGE_URL = "IMAGE_URL";
    private static final String INITIAL_NOTIFICATION_DESCRIPTION =
            "INITIAL_NOTIFICATION_DESCRIPTION";
    private static final String INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT =
            "INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT";
    private static final String INITIAL_PAIRING_DESCRIPTION = "INITIAL_PAIRING_DESCRIPTION";
    private static final String INTENT_URI = "INTENT_URI";
    private static final String LOCALE = "LOCALE";
    private static final String OPEN_COMPANION_APP_DESCRIPTION = "OPEN_COMPANION_APP_DESCRIPTION";
    private static final String RETRO_ACTIVE_PAIRING_DESCRIPTION =
            "RETRO_ACTIVE_PAIRING_DESCRIPTION";
    private static final String SUBSEQUENT_PAIRING_DESCRIPTION = "SUBSEQUENT_PAIRING_DESCRIPTION";
    private static final String SYNC_CONTACT_DESCRPTION = "SYNC_CONTACT_DESCRPTION";
    private static final String SYNC_CONTACTS_TITLE = "SYNC_CONTACTS_TITLE";
    private static final String SYNC_SMS_DESCRIPTION = "SYNC_SMS_DESCRIPTION";
    private static final String SYNC_SMS_TITLE = "SYNC_SMS_TITLE";
    private static final float TRIGGER_DISTANCE = 111;
    private static final String TRUE_WIRELESS_IMAGE_URL_CASE = "TRUE_WIRELESS_IMAGE_URL_CASE";
    private static final String TRUE_WIRELESS_IMAGE_URL_LEFT_BUD =
            "TRUE_WIRELESS_IMAGE_URL_LEFT_BUD";
    private static final String TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD =
            "TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD";
    private static final String UNABLE_TO_CONNECT_DESCRIPTION = "UNABLE_TO_CONNECT_DESCRIPTION";
    private static final String UNABLE_TO_CONNECT_TITLE = "UNABLE_TO_CONNECT_TITLE";
    private static final String UPDATE_COMPANION_APP_DESCRIPTION =
            "UPDATE_COMPANION_APP_DESCRIPTION";
    private static final String WAIT_LAUNCH_COMPANION_APP_DESCRIPTION =
            "WAIT_LAUNCH_COMPANION_APP_DESCRIPTION";
    private static final byte[] ACCOUNT_KEY = new byte[] {3};
    private static final byte[] SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS = new byte[] {2, 8};
    private static final byte[] REQUEST_MODEL_ID = new byte[] {1, 2, 3};
    private static final byte[] ANTI_SPOOFING_KEY = new byte[] {4, 5, 6};
    private static final String ACTION_URL = "ACTION_URL";
    private static final int ACTION_URL_TYPE = 5;
    private static final String APP_NAME = "APP_NAME";
    private static final int ATTACHMENT_TYPE = 8;
    private static final byte[] AUTHENTICATION_PUBLIC_KEY_SEC_P256R1 = new byte[] {5, 7};
    private static final byte[] BLE_RECORD_BYTES = new byte[]{2, 4};
    private static final int DEBUG_CATEGORY = 9;
    private static final String DEBUG_MESSAGE = "DEBUG_MESSAGE";
    private static final String DESCRIPTION = "DESCRIPTION";
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String DISPLAY_URL = "DISPLAY_URL";
    private static final String ENTITY_ID = "ENTITY_ID";
    private static final String FEATURE_GRAPHIC_URL = "FEATURE_GRAPHIC_URL";
    private static final long FIRST_OBSERVATION_TIMESTAMP_MILLIS = 8393L;
    private static final String GROUP_ID = "GROUP_ID";
    private static final String  ICON_FIFE_URL = "ICON_FIFE_URL";
    private static final byte[]  ICON_PNG = new byte[]{2, 5};
    private static final String ID = "ID";
    private static final long LAST_OBSERVATION_TIMESTAMP_MILLIS = 934234L;
    private static final int LAST_USER_EXPERIENCE = 93;
    private static final long LOST_MILLIS = 393284L;
    private static final String MAC_ADDRESS = "MAC_ADDRESS";
    private static final String NAME = "NAME";
    private static final String PACKAGE_NAME = "PACKAGE_NAME";
    private static final long PENDING_APP_INSTALL_TIMESTAMP_MILLIS = 832393L;
    private static final int RSSI = 9;
    private static final int STATE = 63;
    private static final String TITLE = "TITLE";
    private static final String TRIGGER_ID = "TRIGGER_ID";
    private static final int TX_POWER = 62;
    private static final int TYPE = 73;
    private static final String BLE_ADDRESS = "BLE_ADDRESS";

    private static final int ELIGIBLE_ACCOUNTS_NUM = 2;
    private static final ImmutableList<FastPairEligibleAccount> ELIGIBLE_ACCOUNTS =
            ImmutableList.of(
                    genHappyPathFastPairEligibleAccount(ELIGIBLE_ACCOUNT_1,
                            ELIGIBLE_ACCOUNT_1_OPT_IN),
                    genHappyPathFastPairEligibleAccount(ELIGIBLE_ACCOUNT_2,
                            ELIGIBLE_ACCOUNT_2_OPT_IN));
    private static final int ACCOUNTKEY_DEVICE_NUM = 2;
    private static final ImmutableList<FastPairAccountKeyDeviceMetadata>
            FAST_PAIR_ACCOUNT_DEVICES_METADATA =
            ImmutableList.of(
                    genHappyPathFastPairAccountkeyDeviceMetadata(),
                    genHappyPathFastPairAccountkeyDeviceMetadata());

    private static final FastPairAntispoofkeyDeviceMetadataRequestParcel
            FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL =
            genFastPairAntispoofkeyDeviceMetadataRequestParcel();
    private static final FastPairAccountDevicesMetadataRequestParcel
            FAST_PAIR_ACCOUNT_DEVICES_METADATA_REQUEST_PARCEL =
            genFastPairAccountDevicesMetadataRequestParcel();
    private static final FastPairEligibleAccountsRequestParcel
            FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL =
            genFastPairEligibleAccountsRequestParcel();
    private static final FastPairManageAccountRequestParcel
            FAST_PAIR_MANAGE_ACCOUNT_REQUEST_PARCEL =
            genFastPairManageAccountRequestParcel();
    private static final FastPairManageAccountDeviceRequestParcel
            FAST_PAIR_MANAGE_ACCOUNT_DEVICE_REQUEST_PARCEL =
            genFastPairManageAccountDeviceRequestParcel();
    private static final FastPairAntispoofkeyDeviceMetadata
            HAPPY_PATH_FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA =
            genHappyPathFastPairAntispoofkeyDeviceMetadata();

    @Captor private ArgumentCaptor<FastPairEligibleAccountParcel[]>
            mFastPairEligibleAccountParcelsArgumentCaptor;
    @Captor private ArgumentCaptor<FastPairAccountKeyDeviceMetadataParcel[]>
            mFastPairAccountKeyDeviceMetadataParcelsArgumentCaptor;

    @Mock private FastPairDataProviderBase mMockFastPairDataProviderBase;
    @Mock private IFastPairAntispoofkeyDeviceMetadataCallback.Stub
            mAntispoofkeyDeviceMetadataCallback;
    @Mock private IFastPairAccountDevicesMetadataCallback.Stub mAccountDevicesMetadataCallback;
    @Mock private IFastPairEligibleAccountsCallback.Stub mEligibleAccountsCallback;
    @Mock private IFastPairManageAccountCallback.Stub mManageAccountCallback;
    @Mock private IFastPairManageAccountDeviceCallback.Stub mManageAccountDeviceCallback;

    private MyHappyPathProvider mHappyPathFastPairDataProvider;
    private MyErrorPathProvider mErrorPathFastPairDataProvider;

    @Before
    public void setUp() throws Exception {
        initMocks(this);

        mHappyPathFastPairDataProvider =
                new MyHappyPathProvider(TAG, mMockFastPairDataProviderBase);
        mErrorPathFastPairDataProvider =
                new MyErrorPathProvider(TAG, mMockFastPairDataProviderBase);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathLoadFastPairAntispoofkeyDeviceMetadata() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairAntispoofkeyDeviceMetadata(
                FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL,
                mAntispoofkeyDeviceMetadataCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest>
                fastPairAntispoofkeyDeviceMetadataRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                fastPairAntispoofkeyDeviceMetadataRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        ensureHappyPathAsExpected(fastPairAntispoofkeyDeviceMetadataRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        final ArgumentCaptor<FastPairAntispoofkeyDeviceMetadataParcel>
                fastPairAntispoofkeyDeviceMetadataParcelCaptor =
                ArgumentCaptor.forClass(FastPairAntispoofkeyDeviceMetadataParcel.class);
        verify(mAntispoofkeyDeviceMetadataCallback).onFastPairAntispoofkeyDeviceMetadataReceived(
                fastPairAntispoofkeyDeviceMetadataParcelCaptor.capture());
        ensureHappyPathAsExpected(fastPairAntispoofkeyDeviceMetadataParcelCaptor.getValue());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathLoadFastPairAccountDevicesMetadata() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairAccountDevicesMetadata(
                FAST_PAIR_ACCOUNT_DEVICES_METADATA_REQUEST_PARCEL,
                mAccountDevicesMetadataCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest>
                fastPairAccountDevicesMetadataRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAccountDevicesMetadata(
                fastPairAccountDevicesMetadataRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataCallback.class));
        ensureHappyPathAsExpected(fastPairAccountDevicesMetadataRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        verify(mAccountDevicesMetadataCallback).onFastPairAccountDevicesMetadataReceived(
                mFastPairAccountKeyDeviceMetadataParcelsArgumentCaptor.capture());
        ensureHappyPathAsExpected(
                mFastPairAccountKeyDeviceMetadataParcelsArgumentCaptor.getValue());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathLoadFastPairEligibleAccounts() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL,
                mEligibleAccountsCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairEligibleAccountsRequest>
                fastPairEligibleAccountsRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairEligibleAccountsRequest.class);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                fastPairEligibleAccountsRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        ensureHappyPathAsExpected(fastPairEligibleAccountsRequestCaptor.getValue());

        // AOSP receives responses and verifies that it is as expected.
        verify(mEligibleAccountsCallback).onFastPairEligibleAccountsReceived(
                mFastPairEligibleAccountParcelsArgumentCaptor.capture());
        ensureHappyPathAsExpected(mFastPairEligibleAccountParcelsArgumentCaptor.getValue());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathManageFastPairAccount() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().manageFastPairAccount(
                FAST_PAIR_MANAGE_ACCOUNT_REQUEST_PARCEL,
                mManageAccountCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairManageAccountRequest>
                fastPairManageAccountRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairManageAccountRequest.class);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccount(
                fastPairManageAccountRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        ensureHappyPathAsExpected(fastPairManageAccountRequestCaptor.getValue());

        // AOSP receives SUCCESS response.
        verify(mManageAccountCallback).onSuccess();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathManageFastPairAccountDevice() throws Exception {
        // AOSP sends calls to OEM via Parcelable.
        mHappyPathFastPairDataProvider.asProvider().manageFastPairAccountDevice(
                FAST_PAIR_MANAGE_ACCOUNT_DEVICE_REQUEST_PARCEL,
                mManageAccountDeviceCallback);

        // OEM receives request and verifies that it is as expected.
        final ArgumentCaptor<FastPairDataProviderBase.FastPairManageAccountDeviceRequest>
                fastPairManageAccountDeviceRequestCaptor =
                ArgumentCaptor.forClass(
                        FastPairDataProviderBase.FastPairManageAccountDeviceRequest.class);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccountDevice(
                fastPairManageAccountDeviceRequestCaptor.capture(),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        ensureHappyPathAsExpected(fastPairManageAccountDeviceRequestCaptor.getValue());

        // AOSP receives SUCCESS response.
        verify(mManageAccountDeviceCallback).onSuccess();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testErrorPathLoadFastPairAntispoofkeyDeviceMetadata() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairAntispoofkeyDeviceMetadata(
                FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA_REQUEST_PARCEL,
                mAntispoofkeyDeviceMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAntispoofkeyDeviceMetadata(
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataCallback.class));
        verify(mAntispoofkeyDeviceMetadataCallback).onError(
                eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testErrorPathLoadFastPairAccountDevicesMetadata() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairAccountDevicesMetadata(
                FAST_PAIR_ACCOUNT_DEVICES_METADATA_REQUEST_PARCEL,
                mAccountDevicesMetadataCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairAccountDevicesMetadata(
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest.class),
                any(FastPairDataProviderBase.FastPairAccountDevicesMetadataCallback.class));
        verify(mAccountDevicesMetadataCallback).onError(
                eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testErrorPathLoadFastPairEligibleAccounts() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().loadFastPairEligibleAccounts(
                FAST_PAIR_ELIGIBLE_ACCOUNTS_REQUEST_PARCEL,
                mEligibleAccountsCallback);
        verify(mMockFastPairDataProviderBase).onLoadFastPairEligibleAccounts(
                any(FastPairDataProviderBase.FastPairEligibleAccountsRequest.class),
                any(FastPairDataProviderBase.FastPairEligibleAccountsCallback.class));
        verify(mEligibleAccountsCallback).onError(
                eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testErrorPathManageFastPairAccount() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().manageFastPairAccount(
                FAST_PAIR_MANAGE_ACCOUNT_REQUEST_PARCEL,
                mManageAccountCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccount(
                any(FastPairDataProviderBase.FastPairManageAccountRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountCallback).onError(eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testErrorPathManageFastPairAccountDevice() throws Exception {
        mErrorPathFastPairDataProvider.asProvider().manageFastPairAccountDevice(
                FAST_PAIR_MANAGE_ACCOUNT_DEVICE_REQUEST_PARCEL,
                mManageAccountDeviceCallback);
        verify(mMockFastPairDataProviderBase).onManageFastPairAccountDevice(
                any(FastPairDataProviderBase.FastPairManageAccountDeviceRequest.class),
                any(FastPairDataProviderBase.FastPairManageActionCallback.class));
        verify(mManageAccountDeviceCallback).onError(eq(ERROR_CODE_BAD_REQUEST), eq(ERROR_STRING));
    }

    public static class MyHappyPathProvider extends FastPairDataProviderBase {

        private final FastPairDataProviderBase mMockFastPairDataProviderBase;

        public MyHappyPathProvider(@NonNull String tag, FastPairDataProviderBase mock) {
            super(tag);
            mMockFastPairDataProviderBase = mock;
        }

        public IFastPairDataProvider asProvider() {
            return IFastPairDataProvider.Stub.asInterface(getBinder());
        }

        @Override
        public void onLoadFastPairAntispoofkeyDeviceMetadata(
                @NonNull FastPairAntispoofkeyDeviceMetadataRequest request,
                @NonNull FastPairAntispoofkeyDeviceMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAntispoofkeyDeviceMetadata(
                    request, callback);
            callback.onFastPairAntispoofkeyDeviceMetadataReceived(
                    HAPPY_PATH_FAST_PAIR_ANTI_SPOOF_KEY_DEVICE_METADATA);
        }

        @Override
        public void onLoadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequest request,
                @NonNull FastPairAccountDevicesMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAccountDevicesMetadata(
                    request, callback);
            callback.onFastPairAccountDevicesMetadataReceived(FAST_PAIR_ACCOUNT_DEVICES_METADATA);
        }

        @Override
        public void onLoadFastPairEligibleAccounts(
                @NonNull FastPairEligibleAccountsRequest request,
                @NonNull FastPairEligibleAccountsCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairEligibleAccounts(
                    request, callback);
            callback.onFastPairEligibleAccountsReceived(ELIGIBLE_ACCOUNTS);
        }

        @Override
        public void onManageFastPairAccount(
                @NonNull FastPairManageAccountRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccount(request, callback);
            callback.onSuccess();
        }

        @Override
        public void onManageFastPairAccountDevice(
                @NonNull FastPairManageAccountDeviceRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccountDevice(request, callback);
            callback.onSuccess();
        }
    }

    public static class MyErrorPathProvider extends FastPairDataProviderBase {

        private final FastPairDataProviderBase mMockFastPairDataProviderBase;

        public MyErrorPathProvider(@NonNull String tag, FastPairDataProviderBase mock) {
            super(tag);
            mMockFastPairDataProviderBase = mock;
        }

        public IFastPairDataProvider asProvider() {
            return IFastPairDataProvider.Stub.asInterface(getBinder());
        }

        @Override
        public void onLoadFastPairAntispoofkeyDeviceMetadata(
                @NonNull FastPairAntispoofkeyDeviceMetadataRequest request,
                @NonNull FastPairAntispoofkeyDeviceMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAntispoofkeyDeviceMetadata(
                    request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onLoadFastPairAccountDevicesMetadata(
                @NonNull FastPairAccountDevicesMetadataRequest request,
                @NonNull FastPairAccountDevicesMetadataCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairAccountDevicesMetadata(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onLoadFastPairEligibleAccounts(
                @NonNull FastPairEligibleAccountsRequest request,
                @NonNull FastPairEligibleAccountsCallback callback) {
            mMockFastPairDataProviderBase.onLoadFastPairEligibleAccounts(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onManageFastPairAccount(
                @NonNull FastPairManageAccountRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccount(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }

        @Override
        public void onManageFastPairAccountDevice(
                @NonNull FastPairManageAccountDeviceRequest request,
                @NonNull FastPairManageActionCallback callback) {
            mMockFastPairDataProviderBase.onManageFastPairAccountDevice(request, callback);
            callback.onError(ERROR_CODE_BAD_REQUEST, ERROR_STRING);
        }
    }

    /* Generates AntispoofkeyDeviceMetadataRequestParcel. */
    private static FastPairAntispoofkeyDeviceMetadataRequestParcel
            genFastPairAntispoofkeyDeviceMetadataRequestParcel() {
        FastPairAntispoofkeyDeviceMetadataRequestParcel requestParcel =
                new FastPairAntispoofkeyDeviceMetadataRequestParcel();
        requestParcel.modelId = REQUEST_MODEL_ID;

        return requestParcel;
    }

    /* Generates AccountDevicesMetadataRequestParcel. */
    private static FastPairAccountDevicesMetadataRequestParcel
            genFastPairAccountDevicesMetadataRequestParcel() {
        FastPairAccountDevicesMetadataRequestParcel requestParcel =
                new FastPairAccountDevicesMetadataRequestParcel();

        requestParcel.account = ACCOUNTDEVICES_METADATA_ACCOUNT;

        return requestParcel;
    }

    /* Generates FastPairEligibleAccountsRequestParcel. */
    private static FastPairEligibleAccountsRequestParcel
            genFastPairEligibleAccountsRequestParcel() {
        FastPairEligibleAccountsRequestParcel requestParcel =
                new FastPairEligibleAccountsRequestParcel();
        // No fields since FastPairEligibleAccountsRequestParcel is just a place holder now.
        return requestParcel;
    }

    /* Generates FastPairManageAccountRequestParcel. */
    private static FastPairManageAccountRequestParcel
            genFastPairManageAccountRequestParcel() {
        FastPairManageAccountRequestParcel requestParcel =
                new FastPairManageAccountRequestParcel();
        requestParcel.account = MANAGE_ACCOUNT;
        requestParcel.requestType = MANAGE_ACCOUNT_REQUEST_TYPE;

        return requestParcel;
    }

    /* Generates FastPairManageAccountDeviceRequestParcel. */
    private static FastPairManageAccountDeviceRequestParcel
            genFastPairManageAccountDeviceRequestParcel() {
        FastPairManageAccountDeviceRequestParcel requestParcel =
                new FastPairManageAccountDeviceRequestParcel();
        requestParcel.account = MANAGE_ACCOUNT;
        requestParcel.requestType = MANAGE_ACCOUNT_REQUEST_TYPE;
        requestParcel.bleAddress = BLE_ADDRESS;
        requestParcel.accountKeyDeviceMetadata =
                genHappyPathFastPairAccountkeyDeviceMetadataParcel();

        return requestParcel;
    }

    /* Generates Happy Path AntispoofkeyDeviceMetadata. */
    private static FastPairAntispoofkeyDeviceMetadata
            genHappyPathFastPairAntispoofkeyDeviceMetadata() {
        FastPairAntispoofkeyDeviceMetadata.Builder builder =
                new FastPairAntispoofkeyDeviceMetadata.Builder();
        builder.setAntiSpoofPublicKey(ANTI_SPOOFING_KEY);
        builder.setFastPairDeviceMetadata(genHappyPathFastPairDeviceMetadata());

        return builder.build();
    }

    /* Generates Happy Path FastPairAccountKeyDeviceMetadata. */
    private static FastPairAccountKeyDeviceMetadata
            genHappyPathFastPairAccountkeyDeviceMetadata() {
        FastPairAccountKeyDeviceMetadata.Builder builder =
                new FastPairAccountKeyDeviceMetadata.Builder();
        builder.setAccountKey(ACCOUNT_KEY);
        builder.setFastPairDeviceMetadata(genHappyPathFastPairDeviceMetadata());
        builder.setSha256AccountKeyPublicAddress(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS);
        builder.setFastPairDiscoveryItem(genHappyPathFastPairDiscoveryItem());

        return builder.build();
    }

    /* Generates Happy Path FastPairAccountKeyDeviceMetadataParcel. */
    private static FastPairAccountKeyDeviceMetadataParcel
            genHappyPathFastPairAccountkeyDeviceMetadataParcel() {
        FastPairAccountKeyDeviceMetadataParcel parcel =
                new FastPairAccountKeyDeviceMetadataParcel();
        parcel.accountKey = ACCOUNT_KEY;
        parcel.metadata = genHappyPathFastPairDeviceMetadataParcel();
        parcel.sha256AccountKeyPublicAddress = SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS;
        parcel.discoveryItem = genHappyPathFastPairDiscoveryItemParcel();

        return parcel;
    }

    /* Generates Happy Path DiscoveryItem. */
    private static FastPairDiscoveryItem genHappyPathFastPairDiscoveryItem() {
        FastPairDiscoveryItem.Builder builder = new FastPairDiscoveryItem.Builder();

        builder.setActionUrl(ACTION_URL);
        builder.setActionUrlType(ACTION_URL_TYPE);
        builder.setAppName(APP_NAME);
        builder.setAttachmentType(ATTACHMENT_TYPE);
        builder.setAuthenticationPublicKeySecp256r1(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1);
        builder.setBleRecordBytes(BLE_RECORD_BYTES);
        builder.setDebugCategory(DEBUG_CATEGORY);
        builder.setDebugMessage(DEBUG_MESSAGE);
        builder.setDescription(DESCRIPTION);
        builder.setDeviceName(DEVICE_NAME);
        builder.setDisplayUrl(DISPLAY_URL);
        builder.setEntityId(ENTITY_ID);
        builder.setFeatureGraphicUrl(FEATURE_GRAPHIC_URL);
        builder.setFirstObservationTimestampMillis(FIRST_OBSERVATION_TIMESTAMP_MILLIS);
        builder.setGroupId(GROUP_ID);
        builder.setIconFfeUrl(ICON_FIFE_URL);
        builder.setIconPng(ICON_PNG);
        builder.setId(ID);
        builder.setLastObservationTimestampMillis(LAST_OBSERVATION_TIMESTAMP_MILLIS);
        builder.setLastUserExperience(LAST_USER_EXPERIENCE);
        builder.setLostMillis(LOST_MILLIS);
        builder.setMacAddress(MAC_ADDRESS);
        builder.setPackageName(PACKAGE_NAME);
        builder.setPendingAppInstallTimestampMillis(PENDING_APP_INSTALL_TIMESTAMP_MILLIS);
        builder.setRssi(RSSI);
        builder.setState(STATE);
        builder.setTitle(TITLE);
        builder.setTriggerId(TRIGGER_ID);
        builder.setTxPower(TX_POWER);
        builder.setType(TYPE);

        return builder.build();
    }

    /* Generates Happy Path DiscoveryItemParcel. */
    private static FastPairDiscoveryItemParcel genHappyPathFastPairDiscoveryItemParcel() {
        FastPairDiscoveryItemParcel parcel = new FastPairDiscoveryItemParcel();

        parcel.actionUrl = ACTION_URL;
        parcel.actionUrlType = ACTION_URL_TYPE;
        parcel.appName = APP_NAME;
        parcel.attachmentType = ATTACHMENT_TYPE;
        parcel.authenticationPublicKeySecp256r1 = AUTHENTICATION_PUBLIC_KEY_SEC_P256R1;
        parcel.bleRecordBytes = BLE_RECORD_BYTES;
        parcel.debugCategory = DEBUG_CATEGORY;
        parcel.debugMessage = DEBUG_MESSAGE;
        parcel.description = DESCRIPTION;
        parcel.deviceName = DEVICE_NAME;
        parcel.displayUrl = DISPLAY_URL;
        parcel.entityId = ENTITY_ID;
        parcel.featureGraphicUrl = FEATURE_GRAPHIC_URL;
        parcel.firstObservationTimestampMillis = FIRST_OBSERVATION_TIMESTAMP_MILLIS;
        parcel.groupId = GROUP_ID;
        parcel.iconFifeUrl = ICON_FIFE_URL;
        parcel.iconPng = ICON_PNG;
        parcel.id = ID;
        parcel.lastObservationTimestampMillis = LAST_OBSERVATION_TIMESTAMP_MILLIS;
        parcel.lastUserExperience = LAST_USER_EXPERIENCE;
        parcel.lostMillis = LOST_MILLIS;
        parcel.macAddress = MAC_ADDRESS;
        parcel.packageName = PACKAGE_NAME;
        parcel.pendingAppInstallTimestampMillis = PENDING_APP_INSTALL_TIMESTAMP_MILLIS;
        parcel.rssi = RSSI;
        parcel.state = STATE;
        parcel.title = TITLE;
        parcel.triggerId = TRIGGER_ID;
        parcel.txPower = TX_POWER;
        parcel.type = TYPE;

        return parcel;
    }

    /* Generates Happy Path DeviceMetadata. */
    private static FastPairDeviceMetadata genHappyPathFastPairDeviceMetadata() {
        FastPairDeviceMetadata.Builder builder = new FastPairDeviceMetadata.Builder();
        builder.setAssistantSetupHalfSheet(ASSISTANT_SETUP_HALFSHEET);
        builder.setAssistantSetupNotification(ASSISTANT_SETUP_NOTIFICATION);
        builder.setBleTxPower(BLE_TX_POWER);
        builder.setConfirmPinDescription(CONFIRM_PIN_DESCRIPTION);
        builder.setConfirmPinTitle(CONFIRM_PIN_TITLE);
        builder.setConnectSuccessCompanionAppInstalled(CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        builder.setConnectSuccessCompanionAppNotInstalled(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        builder.setDeviceType(DEVICE_TYPE);
        builder.setDownloadCompanionAppDescription(DOWNLOAD_COMPANION_APP_DESCRIPTION);
        builder.setFailConnectGoToSettingsDescription(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        builder.setFastPairTvConnectDeviceNoAccountDescription(
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION);
        builder.setImage(IMAGE);
        builder.setImageUrl(IMAGE_URL);
        builder.setInitialNotificationDescription(INITIAL_NOTIFICATION_DESCRIPTION);
        builder.setInitialNotificationDescriptionNoAccount(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        builder.setInitialPairingDescription(INITIAL_PAIRING_DESCRIPTION);
        builder.setIntentUri(INTENT_URI);
        builder.setLocale(LOCALE);
        builder.setName(NAME);
        builder.setOpenCompanionAppDescription(OPEN_COMPANION_APP_DESCRIPTION);
        builder.setRetroactivePairingDescription(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        builder.setSubsequentPairingDescription(SUBSEQUENT_PAIRING_DESCRIPTION);
        builder.setSyncContactsDescription(SYNC_CONTACT_DESCRPTION);
        builder.setSyncContactsTitle(SYNC_CONTACTS_TITLE);
        builder.setSyncSmsDescription(SYNC_SMS_DESCRIPTION);
        builder.setSyncSmsTitle(SYNC_SMS_TITLE);
        builder.setTriggerDistance(TRIGGER_DISTANCE);
        builder.setTrueWirelessImageUrlCase(TRUE_WIRELESS_IMAGE_URL_CASE);
        builder.setTrueWirelessImageUrlLeftBud(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        builder.setTrueWirelessImageUrlRightBud(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        builder.setUnableToConnectDescription(UNABLE_TO_CONNECT_DESCRIPTION);
        builder.setUnableToConnectTitle(UNABLE_TO_CONNECT_TITLE);
        builder.setUpdateCompanionAppDescription(UPDATE_COMPANION_APP_DESCRIPTION);
        builder.setWaitLaunchCompanionAppDescription(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);

        return builder.build();
    }

    /* Generates Happy Path DeviceMetadataParcel. */
    private static FastPairDeviceMetadataParcel genHappyPathFastPairDeviceMetadataParcel() {
        FastPairDeviceMetadataParcel parcel = new FastPairDeviceMetadataParcel();

        parcel.assistantSetupHalfSheet = ASSISTANT_SETUP_HALFSHEET;
        parcel.assistantSetupNotification = ASSISTANT_SETUP_NOTIFICATION;
        parcel.bleTxPower = BLE_TX_POWER;
        parcel.confirmPinDescription = CONFIRM_PIN_DESCRIPTION;
        parcel.confirmPinTitle = CONFIRM_PIN_TITLE;
        parcel.connectSuccessCompanionAppInstalled = CONNECT_SUCCESS_COMPANION_APP_INSTALLED;
        parcel.connectSuccessCompanionAppNotInstalled =
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED;
        parcel.deviceType = DEVICE_TYPE;
        parcel.downloadCompanionAppDescription = DOWNLOAD_COMPANION_APP_DESCRIPTION;
        parcel.failConnectGoToSettingsDescription = FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION;
        parcel.fastPairTvConnectDeviceNoAccountDescription =
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION;
        parcel.image = IMAGE;
        parcel.imageUrl = IMAGE_URL;
        parcel.initialNotificationDescription = INITIAL_NOTIFICATION_DESCRIPTION;
        parcel.initialNotificationDescriptionNoAccount =
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT;
        parcel.initialPairingDescription = INITIAL_PAIRING_DESCRIPTION;
        parcel.intentUri = INTENT_URI;
        parcel.locale = LOCALE;
        parcel.name = NAME;
        parcel.openCompanionAppDescription = OPEN_COMPANION_APP_DESCRIPTION;
        parcel.retroactivePairingDescription = RETRO_ACTIVE_PAIRING_DESCRIPTION;
        parcel.subsequentPairingDescription = SUBSEQUENT_PAIRING_DESCRIPTION;
        parcel.syncContactsDescription = SYNC_CONTACT_DESCRPTION;
        parcel.syncContactsTitle = SYNC_CONTACTS_TITLE;
        parcel.syncSmsDescription = SYNC_SMS_DESCRIPTION;
        parcel.syncSmsTitle = SYNC_SMS_TITLE;
        parcel.triggerDistance = TRIGGER_DISTANCE;
        parcel.trueWirelessImageUrlCase = TRUE_WIRELESS_IMAGE_URL_CASE;
        parcel.trueWirelessImageUrlLeftBud = TRUE_WIRELESS_IMAGE_URL_LEFT_BUD;
        parcel.trueWirelessImageUrlRightBud = TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD;
        parcel.unableToConnectDescription = UNABLE_TO_CONNECT_DESCRIPTION;
        parcel.unableToConnectTitle = UNABLE_TO_CONNECT_TITLE;
        parcel.updateCompanionAppDescription = UPDATE_COMPANION_APP_DESCRIPTION;
        parcel.waitLaunchCompanionAppDescription = WAIT_LAUNCH_COMPANION_APP_DESCRIPTION;

        return parcel;
    }

    /* Generates Happy Path FastPairEligibleAccount. */
    private static FastPairEligibleAccount genHappyPathFastPairEligibleAccount(
            Account account, boolean optIn) {
        FastPairEligibleAccount.Builder builder = new FastPairEligibleAccount.Builder();
        builder.setAccount(account);
        builder.setOptIn(optIn);

        return builder.build();
    }

    /* Verifies Happy Path AntispoofkeyDeviceMetadataRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairAntispoofkeyDeviceMetadataRequest request) {
        assertThat(request.getModelId()).isEqualTo(REQUEST_MODEL_ID);
    }

    /* Verifies Happy Path AccountDevicesMetadataRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairAccountDevicesMetadataRequest request) {
        assertThat(request.getAccount()).isEqualTo(ACCOUNTDEVICES_METADATA_ACCOUNT);
    }

    /* Verifies Happy Path FastPairEligibleAccountsRequest. */
    @SuppressWarnings("UnusedVariable")
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairEligibleAccountsRequest request) {
        // No fields since FastPairEligibleAccountsRequest is just a place holder now.
    }

    /* Verifies Happy Path FastPairManageAccountRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairManageAccountRequest request) {
        assertThat(request.getAccount()).isEqualTo(MANAGE_ACCOUNT);
        assertThat(request.getRequestType()).isEqualTo(MANAGE_ACCOUNT_REQUEST_TYPE);
    }

    /* Verifies Happy Path FastPairManageAccountDeviceRequest. */
    private static void ensureHappyPathAsExpected(
            FastPairDataProviderBase.FastPairManageAccountDeviceRequest request) {
        assertThat(request.getAccount()).isEqualTo(MANAGE_ACCOUNT);
        assertThat(request.getRequestType()).isEqualTo(MANAGE_ACCOUNT_REQUEST_TYPE);
        assertThat(request.getBleAddress()).isEqualTo(BLE_ADDRESS);
        ensureHappyPathAsExpected(request.getAccountKeyDeviceMetadata());
    }

    /* Verifies Happy Path AntispoofkeyDeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(
            FastPairAntispoofkeyDeviceMetadataParcel metadataParcel) {
        assertThat(metadataParcel).isNotNull();
        assertThat(metadataParcel.antiSpoofPublicKey).isEqualTo(ANTI_SPOOFING_KEY);
        ensureHappyPathAsExpected(metadataParcel.deviceMetadata);
    }

    /* Verifies Happy Path FastPairAccountKeyDeviceMetadataParcel[]. */
    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadataParcel[] metadataParcels) {
        assertThat(metadataParcels).isNotNull();
        assertThat(metadataParcels).hasLength(ACCOUNTKEY_DEVICE_NUM);
        for (FastPairAccountKeyDeviceMetadataParcel parcel: metadataParcels) {
            ensureHappyPathAsExpected(parcel);
        }
    }

    /* Verifies Happy Path FastPairAccountKeyDeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadataParcel metadataParcel) {
        assertThat(metadataParcel).isNotNull();
        assertThat(metadataParcel.accountKey).isEqualTo(ACCOUNT_KEY);
        assertThat(metadataParcel.sha256AccountKeyPublicAddress)
                .isEqualTo(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS);
        ensureHappyPathAsExpected(metadataParcel.metadata);
        ensureHappyPathAsExpected(metadataParcel.discoveryItem);
    }

    /* Verifies Happy Path FastPairAccountKeyDeviceMetadata. */
    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadata metadata) {
        assertThat(metadata.getAccountKey()).isEqualTo(ACCOUNT_KEY);
        assertThat(metadata.getSha256AccountKeyPublicAddress())
                .isEqualTo(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS);
        ensureHappyPathAsExpected(metadata.getFastPairDeviceMetadata());
        ensureHappyPathAsExpected(metadata.getFastPairDiscoveryItem());
    }

    /* Verifies Happy Path DeviceMetadataParcel. */
    private static void ensureHappyPathAsExpected(FastPairDeviceMetadataParcel metadataParcel) {
        assertThat(metadataParcel).isNotNull();

        assertThat(metadataParcel.assistantSetupHalfSheet).isEqualTo(ASSISTANT_SETUP_HALFSHEET);
        assertThat(metadataParcel.assistantSetupNotification).isEqualTo(
                ASSISTANT_SETUP_NOTIFICATION);

        assertThat(metadataParcel.bleTxPower).isEqualTo(BLE_TX_POWER);

        assertThat(metadataParcel.confirmPinDescription).isEqualTo(CONFIRM_PIN_DESCRIPTION);
        assertThat(metadataParcel.confirmPinTitle).isEqualTo(CONFIRM_PIN_TITLE);
        assertThat(metadataParcel.connectSuccessCompanionAppInstalled).isEqualTo(
                CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        assertThat(metadataParcel.connectSuccessCompanionAppNotInstalled).isEqualTo(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);

        assertThat(metadataParcel.deviceType).isEqualTo(DEVICE_TYPE);
        assertThat(metadataParcel.downloadCompanionAppDescription).isEqualTo(
                DOWNLOAD_COMPANION_APP_DESCRIPTION);

        assertThat(metadataParcel.failConnectGoToSettingsDescription).isEqualTo(
                FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        assertThat(metadataParcel.fastPairTvConnectDeviceNoAccountDescription).isEqualTo(
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION);

        assertThat(metadataParcel.image).isEqualTo(IMAGE);
        assertThat(metadataParcel.imageUrl).isEqualTo(IMAGE_URL);
        assertThat(metadataParcel.initialNotificationDescription).isEqualTo(
                INITIAL_NOTIFICATION_DESCRIPTION);
        assertThat(metadataParcel.initialNotificationDescriptionNoAccount).isEqualTo(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        assertThat(metadataParcel.initialPairingDescription).isEqualTo(INITIAL_PAIRING_DESCRIPTION);
        assertThat(metadataParcel.intentUri).isEqualTo(INTENT_URI);

        assertThat(metadataParcel.locale).isEqualTo(LOCALE);
        assertThat(metadataParcel.name).isEqualTo(NAME);

        assertThat(metadataParcel.openCompanionAppDescription).isEqualTo(
                OPEN_COMPANION_APP_DESCRIPTION);

        assertThat(metadataParcel.retroactivePairingDescription).isEqualTo(
                RETRO_ACTIVE_PAIRING_DESCRIPTION);

        assertThat(metadataParcel.subsequentPairingDescription).isEqualTo(
                SUBSEQUENT_PAIRING_DESCRIPTION);
        assertThat(metadataParcel.syncContactsDescription).isEqualTo(SYNC_CONTACT_DESCRPTION);
        assertThat(metadataParcel.syncContactsTitle).isEqualTo(SYNC_CONTACTS_TITLE);
        assertThat(metadataParcel.syncSmsDescription).isEqualTo(SYNC_SMS_DESCRIPTION);
        assertThat(metadataParcel.syncSmsTitle).isEqualTo(SYNC_SMS_TITLE);

        assertThat(metadataParcel.triggerDistance).isWithin(DELTA).of(TRIGGER_DISTANCE);
        assertThat(metadataParcel.trueWirelessImageUrlCase).isEqualTo(TRUE_WIRELESS_IMAGE_URL_CASE);
        assertThat(metadataParcel.trueWirelessImageUrlLeftBud).isEqualTo(
                TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        assertThat(metadataParcel.trueWirelessImageUrlRightBud).isEqualTo(
                TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);

        assertThat(metadataParcel.unableToConnectDescription).isEqualTo(
                UNABLE_TO_CONNECT_DESCRIPTION);
        assertThat(metadataParcel.unableToConnectTitle).isEqualTo(UNABLE_TO_CONNECT_TITLE);
        assertThat(metadataParcel.updateCompanionAppDescription).isEqualTo(
                UPDATE_COMPANION_APP_DESCRIPTION);

        assertThat(metadataParcel.waitLaunchCompanionAppDescription).isEqualTo(
                WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);
    }

    /* Verifies Happy Path DeviceMetadata. */
    private static void ensureHappyPathAsExpected(FastPairDeviceMetadata metadata) {
        assertThat(metadata.getAssistantSetupHalfSheet()).isEqualTo(ASSISTANT_SETUP_HALFSHEET);
        assertThat(metadata.getAssistantSetupNotification())
                .isEqualTo(ASSISTANT_SETUP_NOTIFICATION);
        assertThat(metadata.getBleTxPower()).isEqualTo(BLE_TX_POWER);
        assertThat(metadata.getConfirmPinDescription()).isEqualTo(CONFIRM_PIN_DESCRIPTION);
        assertThat(metadata.getConfirmPinTitle()).isEqualTo(CONFIRM_PIN_TITLE);
        assertThat(metadata.getConnectSuccessCompanionAppInstalled())
                .isEqualTo(CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        assertThat(metadata.getConnectSuccessCompanionAppNotInstalled())
                .isEqualTo(CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        assertThat(metadata.getDeviceType()).isEqualTo(DEVICE_TYPE);
        assertThat(metadata.getDownloadCompanionAppDescription())
                .isEqualTo(DOWNLOAD_COMPANION_APP_DESCRIPTION);
        assertThat(metadata.getFailConnectGoToSettingsDescription())
                .isEqualTo(FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        assertThat(metadata.getFastPairTvConnectDeviceNoAccountDescription())
                .isEqualTo(FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION);
        assertThat(metadata.getImage()).isEqualTo(IMAGE);
        assertThat(metadata.getImageUrl()).isEqualTo(IMAGE_URL);
        assertThat(metadata.getInitialNotificationDescription())
                .isEqualTo(INITIAL_NOTIFICATION_DESCRIPTION);
        assertThat(metadata.getInitialNotificationDescriptionNoAccount())
                .isEqualTo(INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        assertThat(metadata.getInitialPairingDescription()).isEqualTo(INITIAL_PAIRING_DESCRIPTION);
        assertThat(metadata.getIntentUri()).isEqualTo(INTENT_URI);
        assertThat(metadata.getLocale()).isEqualTo(LOCALE);
        assertThat(metadata.getName()).isEqualTo(NAME);
        assertThat(metadata.getOpenCompanionAppDescription())
                .isEqualTo(OPEN_COMPANION_APP_DESCRIPTION);
        assertThat(metadata.getRetroactivePairingDescription())
                .isEqualTo(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        assertThat(metadata.getSubsequentPairingDescription())
                .isEqualTo(SUBSEQUENT_PAIRING_DESCRIPTION);
        assertThat(metadata.getSyncContactsDescription()).isEqualTo(SYNC_CONTACT_DESCRPTION);
        assertThat(metadata.getSyncContactsTitle()).isEqualTo(SYNC_CONTACTS_TITLE);
        assertThat(metadata.getSyncSmsDescription()).isEqualTo(SYNC_SMS_DESCRIPTION);
        assertThat(metadata.getSyncSmsTitle()).isEqualTo(SYNC_SMS_TITLE);
        assertThat(metadata.getTriggerDistance()).isWithin(DELTA).of(TRIGGER_DISTANCE);
        assertThat(metadata.getTrueWirelessImageUrlCase()).isEqualTo(TRUE_WIRELESS_IMAGE_URL_CASE);
        assertThat(metadata.getTrueWirelessImageUrlLeftBud())
                .isEqualTo(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        assertThat(metadata.getTrueWirelessImageUrlRightBud())
                .isEqualTo(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        assertThat(metadata.getUnableToConnectDescription())
                .isEqualTo(UNABLE_TO_CONNECT_DESCRIPTION);
        assertThat(metadata.getUnableToConnectTitle()).isEqualTo(UNABLE_TO_CONNECT_TITLE);
        assertThat(metadata.getUpdateCompanionAppDescription())
                .isEqualTo(UPDATE_COMPANION_APP_DESCRIPTION);
        assertThat(metadata.getWaitLaunchCompanionAppDescription())
                .isEqualTo(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);
    }

    /* Verifies Happy Path FastPairDiscoveryItemParcel. */
    private static void ensureHappyPathAsExpected(FastPairDiscoveryItemParcel itemParcel) {
        assertThat(itemParcel.actionUrl).isEqualTo(ACTION_URL);
        assertThat(itemParcel.actionUrlType).isEqualTo(ACTION_URL_TYPE);
        assertThat(itemParcel.appName).isEqualTo(APP_NAME);
        assertThat(itemParcel.attachmentType).isEqualTo(ATTACHMENT_TYPE);
        assertThat(itemParcel.authenticationPublicKeySecp256r1)
                .isEqualTo(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1);
        assertThat(itemParcel.bleRecordBytes).isEqualTo(BLE_RECORD_BYTES);
        assertThat(itemParcel.debugCategory).isEqualTo(DEBUG_CATEGORY);
        assertThat(itemParcel.debugMessage).isEqualTo(DEBUG_MESSAGE);
        assertThat(itemParcel.description).isEqualTo(DESCRIPTION);
        assertThat(itemParcel.deviceName).isEqualTo(DEVICE_NAME);
        assertThat(itemParcel.displayUrl).isEqualTo(DISPLAY_URL);
        assertThat(itemParcel.entityId).isEqualTo(ENTITY_ID);
        assertThat(itemParcel.featureGraphicUrl).isEqualTo(FEATURE_GRAPHIC_URL);
        assertThat(itemParcel.firstObservationTimestampMillis)
                .isEqualTo(FIRST_OBSERVATION_TIMESTAMP_MILLIS);
        assertThat(itemParcel.groupId).isEqualTo(GROUP_ID);
        assertThat(itemParcel.iconFifeUrl).isEqualTo(ICON_FIFE_URL);
        assertThat(itemParcel.iconPng).isEqualTo(ICON_PNG);
        assertThat(itemParcel.id).isEqualTo(ID);
        assertThat(itemParcel.lastObservationTimestampMillis)
                .isEqualTo(LAST_OBSERVATION_TIMESTAMP_MILLIS);
        assertThat(itemParcel.lastUserExperience).isEqualTo(LAST_USER_EXPERIENCE);
        assertThat(itemParcel.lostMillis).isEqualTo(LOST_MILLIS);
        assertThat(itemParcel.macAddress).isEqualTo(MAC_ADDRESS);
        assertThat(itemParcel.packageName).isEqualTo(PACKAGE_NAME);
        assertThat(itemParcel.pendingAppInstallTimestampMillis)
                .isEqualTo(PENDING_APP_INSTALL_TIMESTAMP_MILLIS);
        assertThat(itemParcel.rssi).isEqualTo(RSSI);
        assertThat(itemParcel.state).isEqualTo(STATE);
        assertThat(itemParcel.title).isEqualTo(TITLE);
        assertThat(itemParcel.triggerId).isEqualTo(TRIGGER_ID);
        assertThat(itemParcel.txPower).isEqualTo(TX_POWER);
        assertThat(itemParcel.type).isEqualTo(TYPE);
    }

    /* Verifies Happy Path FastPairDiscoveryItem. */
    private static void ensureHappyPathAsExpected(FastPairDiscoveryItem item) {
        assertThat(item.getActionUrl()).isEqualTo(ACTION_URL);
        assertThat(item.getActionUrlType()).isEqualTo(ACTION_URL_TYPE);
        assertThat(item.getAppName()).isEqualTo(APP_NAME);
        assertThat(item.getAttachmentType()).isEqualTo(ATTACHMENT_TYPE);
        assertThat(item.getAuthenticationPublicKeySecp256r1())
                .isEqualTo(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1);
        assertThat(item.getBleRecordBytes()).isEqualTo(BLE_RECORD_BYTES);
        assertThat(item.getDebugCategory()).isEqualTo(DEBUG_CATEGORY);
        assertThat(item.getDebugMessage()).isEqualTo(DEBUG_MESSAGE);
        assertThat(item.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(item.getDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(item.getDisplayUrl()).isEqualTo(DISPLAY_URL);
        assertThat(item.getEntityId()).isEqualTo(ENTITY_ID);
        assertThat(item.getFeatureGraphicUrl()).isEqualTo(FEATURE_GRAPHIC_URL);
        assertThat(item.getFirstObservationTimestampMillis())
                .isEqualTo(FIRST_OBSERVATION_TIMESTAMP_MILLIS);
        assertThat(item.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(item.getIconFfeUrl()).isEqualTo(ICON_FIFE_URL);
        assertThat(item.getIconPng()).isEqualTo(ICON_PNG);
        assertThat(item.getId()).isEqualTo(ID);
        assertThat(item.getLastObservationTimestampMillis())
                .isEqualTo(LAST_OBSERVATION_TIMESTAMP_MILLIS);
        assertThat(item.getLastUserExperience()).isEqualTo(LAST_USER_EXPERIENCE);
        assertThat(item.getLostMillis()).isEqualTo(LOST_MILLIS);
        assertThat(item.getMacAddress()).isEqualTo(MAC_ADDRESS);
        assertThat(item.getPackageName()).isEqualTo(PACKAGE_NAME);
        assertThat(item.getPendingAppInstallTimestampMillis())
                .isEqualTo(PENDING_APP_INSTALL_TIMESTAMP_MILLIS);
        assertThat(item.getRssi()).isEqualTo(RSSI);
        assertThat(item.getState()).isEqualTo(STATE);
        assertThat(item.getTitle()).isEqualTo(TITLE);
        assertThat(item.getTriggerId()).isEqualTo(TRIGGER_ID);
        assertThat(item.getTxPower()).isEqualTo(TX_POWER);
        assertThat(item.getType()).isEqualTo(TYPE);
    }

    /* Verifies Happy Path EligibleAccountParcel[]. */
    private static void ensureHappyPathAsExpected(FastPairEligibleAccountParcel[] accountsParcel) {
        assertThat(accountsParcel).hasLength(ELIGIBLE_ACCOUNTS_NUM);

        assertThat(accountsParcel[0].account).isEqualTo(ELIGIBLE_ACCOUNT_1);
        assertThat(accountsParcel[0].optIn).isEqualTo(ELIGIBLE_ACCOUNT_1_OPT_IN);

        assertThat(accountsParcel[1].account).isEqualTo(ELIGIBLE_ACCOUNT_2);
        assertThat(accountsParcel[1].optIn).isEqualTo(ELIGIBLE_ACCOUNT_2_OPT_IN);
    }
}
