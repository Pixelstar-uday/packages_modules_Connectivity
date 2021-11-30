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

package android.nearby;

import static android.nearby.ScanRequest.SCAN_MODE_BALANCED;
import static android.nearby.ScanRequest.SCAN_MODE_LOW_POWER;
import static android.nearby.ScanRequest.SCAN_TYPE_EXPOSURE_NOTIFICATION;
import static android.nearby.ScanRequest.SCAN_TYPE_FAST_PAIR;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_PRESENCE;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_SHARE;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.os.WorkSource;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Units tests for {@link ScanRequest}. */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScanRequestTest {

    private static WorkSource getWorkSource() {
        final int uid = 1001;
        final String appName = "android.nearby.tests";
        return new WorkSource(uid, appName);
    }

    /** Test creating a scan request. */
    @Test
    public void testScanRequestBuilder() {
        final int scanType = SCAN_TYPE_FAST_PAIR;
        ScanRequest request = new ScanRequest.Builder().setScanType(scanType).build();

        assertThat(request.getScanType()).isEqualTo(scanType);
        assertThat(request.getScanMode()).isEqualTo(SCAN_MODE_LOW_POWER);
        // Work source is null if not set.
        assertThat(request.getWorkSource().isEmpty()).isTrue();
    }

    /** Verify RuntimeException is thrown when creating scan request with invalid scan type. */
    @Test(expected = RuntimeException.class)
    public void testScanRequestBuilder_invalidScanType() {
        final int invalidScanType = -1;
        ScanRequest.Builder builder = new ScanRequest.Builder().setScanType(invalidScanType);

        builder.build();
    }

    /** Verify RuntimeException is thrown when creating scan mode with invalid scan mode. */
    @Test(expected = RuntimeException.class)
    public void testScanModeBuilder_invalidScanType() {
        final int invalidScanMode = -5;
        ScanRequest.Builder builder = new ScanRequest.Builder().setScanType(
                SCAN_TYPE_FAST_PAIR).setScanMode(invalidScanMode);
        builder.build();
    }

    /** Verify setting work source in the scan request. */
    @Test
    public void testSetWorkSource() {
        WorkSource workSource = getWorkSource();
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_SHARE)
                .setWorkSource(workSource)
                .build();

        assertThat(request.getWorkSource()).isEqualTo(workSource);
    }

    /** Verify setting work source with null value in the scan request. */
    @Test
    public void testSetWorkSource_nullValue() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_EXPOSURE_NOTIFICATION)
                .setWorkSource(null)
                .build();

        // Null work source is allowed.
        assertThat(request.getWorkSource().isEmpty()).isTrue();
    }

    /** Verify toString returns expected string. */
    @Test
    public void testToString() {
        WorkSource workSource = getWorkSource();
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_SHARE)
                .setScanMode(SCAN_MODE_BALANCED)
                .setEnableBle(true)
                .setWorkSource(workSource)
                .build();

        assertThat(request.toString()).isEqualTo(
                "Request[scanType=2, scanMode=SCAN_MODE_BALANCED, "
                        + "enableBle=true, workSource=WorkSource{1001 android.nearby.tests}]");
    }

    /** Verify toString works correctly with null WorkSource. */
    @Test
    public void testToString_nullWorkSource() {
        ScanRequest request = new ScanRequest.Builder().setScanType(
                SCAN_TYPE_FAST_PAIR).setWorkSource(null).build();

        assertThat(request.toString()).isEqualTo("Request[scanType=1, "
                + "scanMode=SCAN_MODE_LOW_POWER, enableBle=true, workSource=WorkSource{}]");
    }

    /** Verify writing and reading from parcel for scan request. */
    @Test
    public void testParceling() {
        final int scanType = SCAN_TYPE_NEARBY_PRESENCE;
        WorkSource workSource = getWorkSource();
        ScanRequest originalRequest = new ScanRequest.Builder()
                .setScanType(scanType)
                .setScanMode(SCAN_MODE_BALANCED)
                .setEnableBle(true)
                .setWorkSource(workSource)
                .build();

        // Write the scan request to parcel, then read from it.
        ScanRequest request = writeReadFromParcel(originalRequest);

        // Verify the request read from parcel equals to the original request.
        assertThat(request).isEqualTo(originalRequest);
    }

    /** Verify parceling with null WorkSource. */
    @Test
    public void testParceling_nullWorkSource() {
        final int scanType = SCAN_TYPE_NEARBY_PRESENCE;
        ScanRequest originalRequest = new ScanRequest.Builder()
                .setScanType(scanType).build();

        ScanRequest request = writeReadFromParcel(originalRequest);

        assertThat(request).isEqualTo(originalRequest);
    }

    private ScanRequest writeReadFromParcel(ScanRequest originalRequest) {
        Parcel parcel = Parcel.obtain();
        originalRequest.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return ScanRequest.CREATOR.createFromParcel(parcel);
    }
}
