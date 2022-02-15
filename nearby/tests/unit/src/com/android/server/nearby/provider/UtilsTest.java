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

import static com.google.common.truth.Truth.assertThat;

import android.accounts.Account;
import android.nearby.aidl.FastPairAccountKeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairAntispoofkeyDeviceMetadataParcel;
import android.nearby.aidl.FastPairDeviceMetadataParcel;
import android.nearby.aidl.FastPairDiscoveryItemParcel;
import android.nearby.aidl.FastPairEligibleAccountParcel;

import androidx.test.filters.SdkSuppress;

import com.android.server.nearby.fastpair.footprint.FastPairUploadInfo;

import com.google.protobuf.ByteString;

import org.junit.Test;

import java.util.List;

import service.proto.Cache;
import service.proto.Data;
import service.proto.FastPairString.FastPairStrings;
import service.proto.Rpcs;

public class UtilsTest {

    private static final String ASSISTANT_SETUP_HALFSHEET = "ASSISTANT_SETUP_HALFSHEET";
    private static final String ASSISTANT_SETUP_NOTIFICATION = "ASSISTANT_SETUP_NOTIFICATION";
    private static final int BLE_TX_POWER = 5;
    private static final String CONFIRM_PIN_DESCRIPTION = "CONFIRM_PIN_DESCRIPTION";
    private static final String CONFIRM_PIN_TITLE = "CONFIRM_PIN_TITLE";
    private static final String CONNECT_SUCCESS_COMPANION_APP_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_INSTALLED";
    private static final String CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED =
            "CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED";
    private static final int DEVICE_TYPE = 1;
    private static final String DOWNLOAD_COMPANION_APP_DESCRIPTION =
            "DOWNLOAD_COMPANION_APP_DESCRIPTION";
    private static final Account ELIGIBLE_ACCOUNT_1 = new Account("abc@google.com", "type1");
    private static final String FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION =
            "FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION";
    private static final String FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION =
            "FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION";
    private static final byte[] IMAGE = new byte[]{7, 9};
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
    private static final byte[] ACCOUNT_KEY = new byte[]{3};
    private static final byte[] SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS = new byte[]{2, 8};
    private static final byte[] ANTI_SPOOFING_KEY = new byte[]{4, 5, 6};
    private static final String ACTION_URL = "ACTION_URL";
    private static final int ACTION_URL_TYPE = 1;
    private static final String APP_NAME = "APP_NAME";
    private static final int ATTACHMENT_TYPE = 1;
    private static final byte[] AUTHENTICATION_PUBLIC_KEY_SEC_P256R1 = new byte[]{5, 7};
    private static final byte[] BLE_RECORD_BYTES = new byte[]{2, 4};
    private static final int DEBUG_CATEGORY = 1;
    private static final String DEBUG_MESSAGE = "DEBUG_MESSAGE";
    private static final String DESCRIPTION = "DESCRIPTION";
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String DISPLAY_URL = "DISPLAY_URL";
    private static final String ENTITY_ID = "ENTITY_ID";
    private static final String FEATURE_GRAPHIC_URL = "FEATURE_GRAPHIC_URL";
    private static final long FIRST_OBSERVATION_TIMESTAMP_MILLIS = 8393L;
    private static final String GROUP_ID = "GROUP_ID";
    private static final String ICON_FIFE_URL = "ICON_FIFE_URL";
    private static final byte[] ICON_PNG = new byte[]{2, 5};
    private static final String ID = "ID";
    private static final long LAST_OBSERVATION_TIMESTAMP_MILLIS = 934234L;
    private static final int LAST_USER_EXPERIENCE = 1;
    private static final long LOST_MILLIS = 393284L;
    private static final String MAC_ADDRESS = "MAC_ADDRESS";
    private static final String NAME = "NAME";
    private static final String PACKAGE_NAME = "PACKAGE_NAME";
    private static final long PENDING_APP_INSTALL_TIMESTAMP_MILLIS = 832393L;
    private static final int RSSI = 9;
    private static final int STATE = 1;
    private static final String TITLE = "TITLE";
    private static final String TRIGGER_ID = "TRIGGER_ID";
    private static final int TX_POWER = 63;
    private static final int TYPE = 1;

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathConvertToFastPairDevicesWithAccountKey() {
        FastPairAccountKeyDeviceMetadataParcel[] array = {
                genHappyPathFastPairAccountkeyDeviceMetadataParcel()};

        List<Data.FastPairDeviceWithAccountKey> deviceWithAccountKey =
                Utils.convertToFastPairDevicesWithAccountKey(array);
        assertThat(deviceWithAccountKey.size()).isEqualTo(1);
        assertThat(deviceWithAccountKey.get(0)).isEqualTo(
                genHappyPathFastPairDeviceWithAccountKey());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairDevicesWithAccountKeyWithNullArray() {
        FastPairAccountKeyDeviceMetadataParcel[] array = null;
        assertThat(Utils.convertToFastPairDevicesWithAccountKey(array).size()).isEqualTo(0);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairDevicesWithAccountKeyWithNullElement() {
        FastPairAccountKeyDeviceMetadataParcel[] array = {null};
        assertThat(Utils.convertToFastPairDevicesWithAccountKey(array).size()).isEqualTo(0);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairDevicesWithAccountKeyWithEmptyElementNoCrash() {
        FastPairAccountKeyDeviceMetadataParcel[] array = {
                genEmptyFastPairAccountkeyDeviceMetadataParcel()};

        List<Data.FastPairDeviceWithAccountKey> deviceWithAccountKey =
                Utils.convertToFastPairDevicesWithAccountKey(array);
        assertThat(deviceWithAccountKey.size()).isEqualTo(1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairDevicesWithAccountKeyWithEmptyMetadataDiscoveryNoCrash() {
        FastPairAccountKeyDeviceMetadataParcel[] array = {
                genFastPairAccountkeyDeviceMetadataParcelWithEmptyMetadataDiscoveryItem()};

        List<Data.FastPairDeviceWithAccountKey> deviceWithAccountKey =
                Utils.convertToFastPairDevicesWithAccountKey(array);
        assertThat(deviceWithAccountKey.size()).isEqualTo(1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairDevicesWithAccountKeyWithMixedArrayElements() {
        FastPairAccountKeyDeviceMetadataParcel[] array = {
                null,
                genHappyPathFastPairAccountkeyDeviceMetadataParcel(),
                genEmptyFastPairAccountkeyDeviceMetadataParcel(),
                genFastPairAccountkeyDeviceMetadataParcelWithEmptyMetadataDiscoveryItem()};

        assertThat(Utils.convertToFastPairDevicesWithAccountKey(array).size()).isEqualTo(3);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathConvertToAccountList() {
        FastPairEligibleAccountParcel[] array = {genHappyPathFastPairEligibleAccountParcel()};

        List<Account> accountList = Utils.convertToAccountList(array);
        assertThat(accountList.size()).isEqualTo(1);
        assertThat(accountList.get(0)).isEqualTo(ELIGIBLE_ACCOUNT_1);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToAccountListNullArray() {
        FastPairEligibleAccountParcel[] array = null;

        List<Account> accountList = Utils.convertToAccountList(array);
        assertThat(accountList.size()).isEqualTo(0);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToAccountListWithNullElement() {
        FastPairEligibleAccountParcel[] array = {null};

        List<Account> accountList = Utils.convertToAccountList(array);
        assertThat(accountList.size()).isEqualTo(0);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToAccountListWithEmptyElementNotCrash() {
        FastPairEligibleAccountParcel[] array =
                {genEmptyFastPairEligibleAccountParcel()};

        List<Account> accountList = Utils.convertToAccountList(array);
        assertThat(accountList.size()).isEqualTo(0);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToAccountListWithMixedArrayElements() {
        FastPairEligibleAccountParcel[] array = {
                genHappyPathFastPairEligibleAccountParcel(),
                genEmptyFastPairEligibleAccountParcel(),
                null,
                genHappyPathFastPairEligibleAccountParcel()};

        List<Account> accountList = Utils.convertToAccountList(array);
        assertThat(accountList.size()).isEqualTo(2);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathConvertToGetObservedDeviceResponse() {
        Rpcs.GetObservedDeviceResponse response =
                Utils.convertToGetObservedDeviceResponse(
                        genHappyPathFastPairAntispoofkeyDeviceMetadataParcel());
        assertThat(response).isEqualTo(genHappyPathObservedDeviceResponse());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToGetObservedDeviceResponseWithNullInput() {
        assertThat(Utils.convertToGetObservedDeviceResponse(null))
                .isEqualTo(null);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToGetObservedDeviceResponseWithEmptyInputNotCrash() {
        Utils.convertToGetObservedDeviceResponse(
                genEmptyFastPairAntispoofkeyDeviceMetadataParcel());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToGetObservedDeviceResponseWithEmptyDeviceMetadataNotCrash() {
        Utils.convertToGetObservedDeviceResponse(
                genFastPairAntispoofkeyDeviceMetadataParcelWithEmptyDeviceMetadata());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testHappyPathConvertToFastPairAccountKeyDeviceMetadata() {
        FastPairAccountKeyDeviceMetadataParcel metadataParcel =
                Utils.convertToFastPairAccountKeyDeviceMetadata(genHappyPathFastPairUploadInfo());
        ensureHappyPathAsExpected(metadataParcel);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairAccountKeyDeviceMetadataWithNullInput() {
        assertThat(Utils.convertToFastPairAccountKeyDeviceMetadata(null)).isEqualTo(null);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testConvertToFastPairAccountKeyDeviceMetadataWithEmptyFieldsNotCrash() {
        Utils.convertToFastPairAccountKeyDeviceMetadata(
                new FastPairUploadInfo(
                        null /* discoveryItem */,
                        null /* accountKey */,
                        null /* sha256AccountKeyPublicAddress */));
    }

    private static FastPairUploadInfo genHappyPathFastPairUploadInfo() {
        return new FastPairUploadInfo(
                genHappyPathStoredDiscoveryItem(),
                ByteString.copyFrom(ACCOUNT_KEY),
                ByteString.copyFrom(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS));

    }

    private static void ensureHappyPathAsExpected(
            FastPairAccountKeyDeviceMetadataParcel metadataParcel) {
        assertThat(metadataParcel).isNotNull();
        assertThat(metadataParcel.accountKey).isEqualTo(ACCOUNT_KEY);
        assertThat(metadataParcel.sha256AccountKeyPublicAddress)
                .isEqualTo(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS);
        ensureHappyPathAsExpected(metadataParcel.metadata);
        ensureHappyPathAsExpected(metadataParcel.discoveryItem);
    }

    private static void ensureHappyPathAsExpected(FastPairDeviceMetadataParcel metadataParcel) {
        assertThat(metadataParcel).isNotNull();

        assertThat(metadataParcel.assistantSetupHalfSheet).isEqualTo(ASSISTANT_SETUP_HALFSHEET);
        assertThat(metadataParcel.assistantSetupNotification).isEqualTo(
                ASSISTANT_SETUP_NOTIFICATION);


        assertThat(metadataParcel.confirmPinDescription).isEqualTo(CONFIRM_PIN_DESCRIPTION);
        assertThat(metadataParcel.confirmPinTitle).isEqualTo(CONFIRM_PIN_TITLE);
        assertThat(metadataParcel.connectSuccessCompanionAppInstalled).isEqualTo(
                CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        assertThat(metadataParcel.connectSuccessCompanionAppNotInstalled).isEqualTo(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);

        assertThat(metadataParcel.deviceType).isEqualTo(DEVICE_TYPE);

        assertThat(metadataParcel.failConnectGoToSettingsDescription).isEqualTo(
                FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        assertThat(metadataParcel.fastPairTvConnectDeviceNoAccountDescription).isEqualTo(
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION);

        assertThat(metadataParcel.initialNotificationDescription).isEqualTo(
                INITIAL_NOTIFICATION_DESCRIPTION);
        assertThat(metadataParcel.initialNotificationDescriptionNoAccount).isEqualTo(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        assertThat(metadataParcel.initialPairingDescription).isEqualTo(INITIAL_PAIRING_DESCRIPTION);


        assertThat(metadataParcel.retroactivePairingDescription).isEqualTo(
                RETRO_ACTIVE_PAIRING_DESCRIPTION);

        assertThat(metadataParcel.subsequentPairingDescription).isEqualTo(
                SUBSEQUENT_PAIRING_DESCRIPTION);
        assertThat(metadataParcel.syncContactsDescription).isEqualTo(SYNC_CONTACT_DESCRPTION);
        assertThat(metadataParcel.syncContactsTitle).isEqualTo(SYNC_CONTACTS_TITLE);
        assertThat(metadataParcel.syncSmsDescription).isEqualTo(SYNC_SMS_DESCRIPTION);
        assertThat(metadataParcel.syncSmsTitle).isEqualTo(SYNC_SMS_TITLE);

        assertThat(metadataParcel.trueWirelessImageUrlCase).isEqualTo(TRUE_WIRELESS_IMAGE_URL_CASE);
        assertThat(metadataParcel.trueWirelessImageUrlLeftBud).isEqualTo(
                TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        assertThat(metadataParcel.trueWirelessImageUrlRightBud).isEqualTo(
                TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        assertThat(metadataParcel.waitLaunchCompanionAppDescription).isEqualTo(
                WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);

        /* do we need upload this? */
        // assertThat(metadataParcel.locale).isEqualTo(LOCALE);
        // assertThat(metadataParcel.name).isEqualTo(NAME);
        // assertThat(metadataParcel.downloadCompanionAppDescription).isEqualTo(
        //        DOWNLOAD_COMPANION_APP_DESCRIPTION);
        // assertThat(metadataParcel.openCompanionAppDescription).isEqualTo(
        //        OPEN_COMPANION_APP_DESCRIPTION);
        // assertThat(metadataParcel.triggerDistance).isWithin(DELTA).of(TRIGGER_DISTANCE);
        // assertThat(metadataParcel.unableToConnectDescription).isEqualTo(
        //        UNABLE_TO_CONNECT_DESCRIPTION);
        // assertThat(metadataParcel.unableToConnectTitle).isEqualTo(UNABLE_TO_CONNECT_TITLE);
        // assertThat(metadataParcel.updateCompanionAppDescription).isEqualTo(
        //        UPDATE_COMPANION_APP_DESCRIPTION);

        // assertThat(metadataParcel.bleTxPower).isEqualTo(BLE_TX_POWER);
        // assertThat(metadataParcel.image).isEqualTo(IMAGE);
        // assertThat(metadataParcel.imageUrl).isEqualTo(IMAGE_URL);
        // assertThat(metadataParcel.intentUri).isEqualTo(INTENT_URI);
    }

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

    private static FastPairEligibleAccountParcel genHappyPathFastPairEligibleAccountParcel() {
        FastPairEligibleAccountParcel parcel = new FastPairEligibleAccountParcel();
        parcel.account = ELIGIBLE_ACCOUNT_1;
        parcel.optIn = true;

        return parcel;
    }

    private static FastPairEligibleAccountParcel genEmptyFastPairEligibleAccountParcel() {
        return new FastPairEligibleAccountParcel();
    }

    private static FastPairDeviceMetadataParcel genEmptyFastPairDeviceMetadataParcel() {
        return new FastPairDeviceMetadataParcel();
    }

    private static FastPairDiscoveryItemParcel genEmptyFastPairDiscoveryItemParcel() {
        return new FastPairDiscoveryItemParcel();
    }

    private static FastPairAccountKeyDeviceMetadataParcel
            genEmptyFastPairAccountkeyDeviceMetadataParcel() {
        return new FastPairAccountKeyDeviceMetadataParcel();
    }

    private static FastPairAccountKeyDeviceMetadataParcel
            genFastPairAccountkeyDeviceMetadataParcelWithEmptyMetadataDiscoveryItem() {
        FastPairAccountKeyDeviceMetadataParcel parcel =
                new FastPairAccountKeyDeviceMetadataParcel();
        parcel.metadata = genEmptyFastPairDeviceMetadataParcel();
        parcel.discoveryItem = genEmptyFastPairDiscoveryItemParcel();

        return parcel;
    }

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

    private static Cache.StoredDiscoveryItem genHappyPathStoredDiscoveryItem() {
        Cache.StoredDiscoveryItem.Builder storedDiscoveryItemBuilder =
                Cache.StoredDiscoveryItem.newBuilder();
        storedDiscoveryItemBuilder.setActionUrl(ACTION_URL);
        storedDiscoveryItemBuilder.setActionUrlType(Cache.ResolvedUrlType.WEBPAGE);
        storedDiscoveryItemBuilder.setAppName(APP_NAME);
        storedDiscoveryItemBuilder.setAttachmentType(
                Cache.DiscoveryAttachmentType.DISCOVERY_ATTACHMENT_TYPE_NORMAL);
        storedDiscoveryItemBuilder.setAuthenticationPublicKeySecp256R1(
                ByteString.copyFrom(AUTHENTICATION_PUBLIC_KEY_SEC_P256R1));
        storedDiscoveryItemBuilder.setBleRecordBytes(ByteString.copyFrom(BLE_RECORD_BYTES));
        storedDiscoveryItemBuilder.setDebugCategory(
                Cache.StoredDiscoveryItem.DebugMessageCategory.STATUS_VALID_NOTIFICATION);
        storedDiscoveryItemBuilder.setDebugMessage(DEBUG_MESSAGE);
        storedDiscoveryItemBuilder.setDescription(DESCRIPTION);
        storedDiscoveryItemBuilder.setDeviceName(DEVICE_NAME);
        storedDiscoveryItemBuilder.setDisplayUrl(DISPLAY_URL);
        storedDiscoveryItemBuilder.setEntityId(ENTITY_ID);
        storedDiscoveryItemBuilder.setFeatureGraphicUrl(FEATURE_GRAPHIC_URL);
        storedDiscoveryItemBuilder.setFirstObservationTimestampMillis(
                FIRST_OBSERVATION_TIMESTAMP_MILLIS);
        storedDiscoveryItemBuilder.setGroupId(GROUP_ID);
        storedDiscoveryItemBuilder.setIconFifeUrl(ICON_FIFE_URL);
        storedDiscoveryItemBuilder.setIconPng(ByteString.copyFrom(ICON_PNG));
        storedDiscoveryItemBuilder.setId(ID);
        storedDiscoveryItemBuilder.setLastObservationTimestampMillis(
                LAST_OBSERVATION_TIMESTAMP_MILLIS);
        storedDiscoveryItemBuilder.setLastUserExperience(
                Cache.StoredDiscoveryItem.ExperienceType.EXPERIENCE_GOOD);
        storedDiscoveryItemBuilder.setLostMillis(LOST_MILLIS);
        storedDiscoveryItemBuilder.setMacAddress(MAC_ADDRESS);
        storedDiscoveryItemBuilder.setPackageName(PACKAGE_NAME);
        storedDiscoveryItemBuilder.setPendingAppInstallTimestampMillis(
                PENDING_APP_INSTALL_TIMESTAMP_MILLIS);
        storedDiscoveryItemBuilder.setRssi(RSSI);
        storedDiscoveryItemBuilder.setState(Cache.StoredDiscoveryItem.State.STATE_ENABLED);
        storedDiscoveryItemBuilder.setTitle(TITLE);
        storedDiscoveryItemBuilder.setTriggerId(TRIGGER_ID);
        storedDiscoveryItemBuilder.setTxPower(TX_POWER);
        storedDiscoveryItemBuilder.setType(Cache.NearbyType.NEARBY_PROXIMITY_BEACON);

        FastPairStrings.Builder stringsBuilder = FastPairStrings.newBuilder();
        stringsBuilder.setAssistantHalfSheetDescription(ASSISTANT_SETUP_HALFSHEET);
        stringsBuilder.setAssistantNotificationDescription(ASSISTANT_SETUP_NOTIFICATION);
        stringsBuilder.setConfirmPinDescription(CONFIRM_PIN_DESCRIPTION);
        stringsBuilder.setConfirmPinTitle(CONFIRM_PIN_TITLE);
        stringsBuilder.setPairingFinishedCompanionAppInstalled(
                CONNECT_SUCCESS_COMPANION_APP_INSTALLED);
        stringsBuilder.setPairingFinishedCompanionAppNotInstalled(
                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED);
        stringsBuilder.setPairingFailDescription(
                FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION);
        stringsBuilder.setFastPairTvConnectDeviceNoAccountDescription(
                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION);
        stringsBuilder.setTapToPairWithAccount(
                INITIAL_NOTIFICATION_DESCRIPTION);
        stringsBuilder.setTapToPairWithoutAccount(
                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT);
        stringsBuilder.setInitialPairingDescription(INITIAL_PAIRING_DESCRIPTION);
        stringsBuilder.setRetroactivePairingDescription(RETRO_ACTIVE_PAIRING_DESCRIPTION);
        stringsBuilder.setSubsequentPairingDescription(SUBSEQUENT_PAIRING_DESCRIPTION);
        stringsBuilder.setSyncContactsDescription(SYNC_CONTACT_DESCRPTION);
        stringsBuilder.setSyncContactsTitle(SYNC_CONTACTS_TITLE);
        stringsBuilder.setSyncSmsDescription(SYNC_SMS_DESCRIPTION);
        stringsBuilder.setSyncSmsTitle(SYNC_SMS_TITLE);
        stringsBuilder.setWaitAppLaunchDescription(WAIT_LAUNCH_COMPANION_APP_DESCRIPTION);
        storedDiscoveryItemBuilder.setFastPairStrings(stringsBuilder.build());

        Cache.FastPairInformation.Builder fpInformationBuilder =
                Cache.FastPairInformation.newBuilder();
        Rpcs.TrueWirelessHeadsetImages.Builder imagesBuilder =
                Rpcs.TrueWirelessHeadsetImages.newBuilder();
        imagesBuilder.setCaseUrl(TRUE_WIRELESS_IMAGE_URL_CASE);
        imagesBuilder.setLeftBudUrl(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        imagesBuilder.setRightBudUrl(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        fpInformationBuilder.setTrueWirelessImages(imagesBuilder.build());
        fpInformationBuilder.setDeviceType(Rpcs.DeviceType.HEADPHONES);

        storedDiscoveryItemBuilder.setFastPairInformation(fpInformationBuilder.build());
        storedDiscoveryItemBuilder.setTxPower(TX_POWER);

        storedDiscoveryItemBuilder.setIconPng(ByteString.copyFrom(ICON_PNG));
        storedDiscoveryItemBuilder.setIconFifeUrl(ICON_FIFE_URL);
        storedDiscoveryItemBuilder.setActionUrl(ACTION_URL);

        return storedDiscoveryItemBuilder.build();
    }

    private static Data.FastPairDeviceWithAccountKey genHappyPathFastPairDeviceWithAccountKey() {
        Data.FastPairDeviceWithAccountKey.Builder fpDeviceBuilder =
                Data.FastPairDeviceWithAccountKey.newBuilder();
        fpDeviceBuilder.setAccountKey(ByteString.copyFrom(ACCOUNT_KEY));
        fpDeviceBuilder.setSha256AccountKeyPublicAddress(
                ByteString.copyFrom(SHA256_ACCOUNT_KEY_PUBLIC_ADDRESS));
        fpDeviceBuilder.setDiscoveryItem(genHappyPathStoredDiscoveryItem());

        return fpDeviceBuilder.build();
    }

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

    private static Rpcs.GetObservedDeviceResponse genHappyPathObservedDeviceResponse() {
        Rpcs.Device.Builder deviceBuilder = Rpcs.Device.newBuilder();
        deviceBuilder.setAntiSpoofingKeyPair(Rpcs.AntiSpoofingKeyPair.newBuilder()
                .setPublicKey(ByteString.copyFrom(ANTI_SPOOFING_KEY))
                .build());
        Rpcs.TrueWirelessHeadsetImages.Builder imagesBuilder =
                Rpcs.TrueWirelessHeadsetImages.newBuilder();
        imagesBuilder.setLeftBudUrl(TRUE_WIRELESS_IMAGE_URL_LEFT_BUD);
        imagesBuilder.setRightBudUrl(TRUE_WIRELESS_IMAGE_URL_RIGHT_BUD);
        imagesBuilder.setCaseUrl(TRUE_WIRELESS_IMAGE_URL_CASE);
        deviceBuilder.setTrueWirelessImages(imagesBuilder.build());
        deviceBuilder.setImageUrl(IMAGE_URL);
        deviceBuilder.setIntentUri(INTENT_URI);
        deviceBuilder.setName(NAME);
        deviceBuilder.setBleTxPower(BLE_TX_POWER);
        deviceBuilder.setTriggerDistance(TRIGGER_DISTANCE);
        deviceBuilder.setDeviceType(Rpcs.DeviceType.HEADPHONES);

        return Rpcs.GetObservedDeviceResponse.newBuilder()
                .setDevice(deviceBuilder.build())
                .setImage(ByteString.copyFrom(IMAGE))
                .setStrings(Rpcs.ObservedDeviceStrings.newBuilder()
                        .setAssistantSetupHalfSheet(ASSISTANT_SETUP_HALFSHEET)
                        .setAssistantSetupNotification(ASSISTANT_SETUP_NOTIFICATION)
                        .setConfirmPinDescription(CONFIRM_PIN_DESCRIPTION)
                        .setConfirmPinTitle(CONFIRM_PIN_TITLE)
                        .setConnectSuccessCompanionAppInstalled(
                                CONNECT_SUCCESS_COMPANION_APP_INSTALLED)
                        .setConnectSuccessCompanionAppNotInstalled(
                                CONNECT_SUCCESS_COMPANION_APP_NOT_INSTALLED)
                        .setDownloadCompanionAppDescription(
                                DOWNLOAD_COMPANION_APP_DESCRIPTION)
                        .setFailConnectGoToSettingsDescription(
                                FAIL_CONNECT_GOTO_SETTINGS_DESCRIPTION)
                        .setFastPairTvConnectDeviceNoAccountDescription(
                                FAST_PAIR_TV_CONNECT_DEVICE_NO_ACCOUNT_DESCRIPTION)
                        .setInitialNotificationDescription(
                                INITIAL_NOTIFICATION_DESCRIPTION)
                        .setInitialNotificationDescriptionNoAccount(
                                INITIAL_NOTIFICATION_DESCRIPTION_NO_ACCOUNT)
                        .setInitialPairingDescription(
                                INITIAL_PAIRING_DESCRIPTION)
                        .setLocale(LOCALE)
                        .setOpenCompanionAppDescription(
                                OPEN_COMPANION_APP_DESCRIPTION)
                        .setRetroactivePairingDescription(
                                RETRO_ACTIVE_PAIRING_DESCRIPTION)
                        .setSubsequentPairingDescription(
                                SUBSEQUENT_PAIRING_DESCRIPTION)
                        .setSyncContactsDescription(
                                SYNC_CONTACT_DESCRPTION)
                        .setSyncContactsTitle(
                                SYNC_CONTACTS_TITLE)
                        .setSyncSmsDescription(
                                SYNC_SMS_DESCRIPTION)
                        .setSyncSmsTitle(
                                SYNC_SMS_TITLE)
                        .setUnableToConnectDescription(
                                UNABLE_TO_CONNECT_DESCRIPTION)
                        .setUnableToConnectTitle(
                                UNABLE_TO_CONNECT_TITLE)
                        .setUpdateCompanionAppDescription(
                                UPDATE_COMPANION_APP_DESCRIPTION)
                        .setWaitLaunchCompanionAppDescription(
                                WAIT_LAUNCH_COMPANION_APP_DESCRIPTION)
                        .build())
                .build();
    }

    private static FastPairAntispoofkeyDeviceMetadataParcel
            genHappyPathFastPairAntispoofkeyDeviceMetadataParcel() {
        FastPairAntispoofkeyDeviceMetadataParcel parcel =
                new FastPairAntispoofkeyDeviceMetadataParcel();
        parcel.antiSpoofPublicKey = ANTI_SPOOFING_KEY;
        parcel.deviceMetadata = genHappyPathFastPairDeviceMetadataParcel();

        return parcel;
    }

    private static FastPairAntispoofkeyDeviceMetadataParcel
            genFastPairAntispoofkeyDeviceMetadataParcelWithEmptyDeviceMetadata() {
        FastPairAntispoofkeyDeviceMetadataParcel parcel =
                new FastPairAntispoofkeyDeviceMetadataParcel();
        parcel.antiSpoofPublicKey = ANTI_SPOOFING_KEY;
        parcel.deviceMetadata = genEmptyFastPairDeviceMetadataParcel();

        return parcel;
    }

    private static FastPairAntispoofkeyDeviceMetadataParcel
            genEmptyFastPairAntispoofkeyDeviceMetadataParcel() {
        return new FastPairAntispoofkeyDeviceMetadataParcel();
    }
}
