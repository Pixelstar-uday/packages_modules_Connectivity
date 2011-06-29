/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.android.server.NetworkManagementSocketTagger.kernelToTag;
import static com.android.server.NetworkManagementSocketTagger.tagToKernel;

import android.content.res.Resources;
import android.net.NetworkStats;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.frameworks.servicestests.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * Tests for {@link NetworkManagementService}.
 */
@LargeTest
public class NetworkManagementServiceTest extends AndroidTestCase {
    private File mTestProc;
    private NetworkManagementService mService;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mTestProc = getContext().getFilesDir();
        mService = NetworkManagementService.createForTest(mContext, mTestProc);
    }

    @Override
    public void tearDown() throws Exception {
        mService = null;

        super.tearDown();
    }

    public void testNetworkStatsDetail() throws Exception {
        stageFile(R.raw.xt_qtaguid_typical, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mService.getNetworkStatsDetail();
        assertEquals(31, stats.size);
        assertStatsEntry(stats, "wlan0", 0, 0, 14615L, 4270L);
        assertStatsEntry(stats, "wlan0", 10004, 0, 333821L, 53558L);
        assertStatsEntry(stats, "wlan0", 10004, 1947740890, 18725L, 1066L);
        assertStatsEntry(stats, "rmnet0", 10037, 0, 31184994L, 684122L);
        assertStatsEntry(stats, "rmnet0", 10037, 1947740890, 28507378L, 437004L);
    }

    public void testNetworkStatsDetailExtended() throws Exception {
        stageFile(R.raw.xt_qtaguid_extended, new File(mTestProc, "net/xt_qtaguid/stats"));

        final NetworkStats stats = mService.getNetworkStatsDetail();
        assertEquals(2, stats.size);
        assertStatsEntry(stats, "test0", 1000, 0, 1024L, 2048L);
        assertStatsEntry(stats, "test0", 1000, 0xF00D, 512L, 512L);
    }

    public void testKernelTags() throws Exception {
        assertEquals("0", tagToKernel(0x0));
        assertEquals("214748364800", tagToKernel(0x32));
        assertEquals("9223372032559808512", tagToKernel(Integer.MAX_VALUE));
        assertEquals("0", tagToKernel(Integer.MIN_VALUE));
        assertEquals("9223369837831520256", tagToKernel(Integer.MIN_VALUE - 512));

        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(0x32, kernelToTag("0x0000003200000000"));
        assertEquals(2147483647, kernelToTag("0x7fffffff00000000"));
        assertEquals(0, kernelToTag("0x0000000000000000"));
        assertEquals(2147483136, kernelToTag("0x7FFFFE0000000000"));

    }

    /**
     * Copy a {@link Resources#openRawResource(int)} into {@link File} for
     * testing purposes.
     */
    private void stageFile(int rawId, File file) throws Exception {
        new File(file.getParent()).mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContext().getResources().openRawResource(rawId);
            out = new FileOutputStream(file);
            Streams.copy(in, out);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }
    }

    private static void assertStatsEntry(
            NetworkStats stats, String iface, int uid, int tag, long rx, long tx) {
        final int i = stats.findIndex(iface, uid, tag);
        assertEquals(rx, stats.rx[i]);
        assertEquals(tx, stats.tx[i]);
    }

}
