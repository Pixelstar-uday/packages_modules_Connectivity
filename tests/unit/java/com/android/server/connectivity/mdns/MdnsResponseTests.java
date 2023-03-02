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
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static android.net.InetAddresses.parseNumericAddress;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import static java.util.Collections.emptyList;

import android.net.Network;

import com.android.net.module.util.HexDump;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.List;

// The record test data does not use compressed names (label pointers), since that would require
// additional data to populate the label dictionary accordingly.
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsResponseTests {
    private static final String TAG = "MdnsResponseTests";
    // MDNS response packet for name "test" with an IPv4 address of 10.1.2.3
    private static final byte[] dataIn_ipv4_1 = HexDump.hexStringToByteArray(
            "0474657374000001" + "0001000011940004" + "0A010203");
    // MDNS response packet for name "tess" with an IPv4 address of 10.1.2.4
    private static final byte[] dataIn_ipv4_2 = HexDump.hexStringToByteArray(
            "0474657373000001" + "0001000011940004" + "0A010204");
    // MDNS response w/name "test" & IPv6 address of aabb:ccdd:1122:3344:a0b0:c0d0:1020:3040
    private static final byte[] dataIn_ipv6_1 = HexDump.hexStringToByteArray(
            "047465737400001C" + "0001000011940010" + "AABBCCDD11223344" + "A0B0C0D010203040");
    // MDNS response w/name "test" & IPv6 address of aabb:ccdd:1122:3344:a0b0:c0d0:1020:3030
    private static final byte[] dataIn_ipv6_2 = HexDump.hexStringToByteArray(
            "047465737400001C" + "0001000011940010" + "AABBCCDD11223344" + "A0B0C0D010203030");
    // MDNS response w/name "test" & PTR to foo.bar.quxx
    private static final byte[] dataIn_ptr_1 = HexDump.hexStringToByteArray(
            "047465737400000C" + "000100001194000E" + "03666F6F03626172" + "047175787800");
    // MDNS response w/name "test" & PTR to foo.bar.quxy
    private static final byte[] dataIn_ptr_2 = HexDump.hexStringToByteArray(
            "047465737400000C" + "000100001194000E" + "03666F6F03626172" + "047175787900");
    // MDNS response w/name "test" & Service for host foo.bar.quxx
    private static final byte[] dataIn_service_1 = HexDump.hexStringToByteArray(
            "0474657374000021"
            + "0001000011940014"
            + "000100FF1F480366"
            + "6F6F036261720471"
            + "75787800");
    // MDNS response w/name "test" & Service for host test
    private static final byte[] dataIn_service_2 = HexDump.hexStringToByteArray(
            "0474657374000021" + "000100001194000B" + "000100FF1F480474" + "657374");
    // MDNS response w/name "test" & the following text strings:
    // "a=hello there", "b=1234567890", and "xyz=!$$$"
    private static final byte[] dataIn_text_1 = HexDump.hexStringToByteArray(
            "0474657374000010"
            + "0001000011940024"
            + "0D613D68656C6C6F"
            + "2074686572650C62"
            + "3D31323334353637"
            + "3839300878797A3D"
            + "21242424");
    // MDNS response w/name "test" & the following text strings:
    // "a=hello there", "b=1234567890", and "xyz=!@#$"
    private static final byte[] dataIn_text_2 = HexDump.hexStringToByteArray(
            "0474657374000010"
            + "0001000011940024"
            + "0D613D68656C6C6F"
            + "2074686572650C62"
            + "3D31323334353637"
            + "3839300878797A3D"
            + "21402324");

    private static final int INTERFACE_INDEX = 999;
    private static final int TEST_TTL_MS = 120_000;
    private final Network mNetwork = mock(Network.class);

    // The following helper classes act as wrappers so that IPv4 and IPv6 address records can
    // be explicitly created by type using same constructor signature as all other records.
    static class MdnsInet4AddressRecord extends MdnsInetAddressRecord {
        public MdnsInet4AddressRecord(String[] name, MdnsPacketReader reader) throws IOException {
            super(name, MdnsRecord.TYPE_A, reader);
        }
    }

    static class MdnsInet6AddressRecord extends MdnsInetAddressRecord {
        public MdnsInet6AddressRecord(String[] name, MdnsPacketReader reader) throws IOException {
            super(name, MdnsRecord.TYPE_AAAA, reader);
        }
    }

    // This helper class just wraps the data bytes of a response packet with the contained record
    // type.
    // Its only purpose is to make the test code a bit more readable.
    static class PacketAndRecordClass {
        public final byte[] packetData;
        public final Class<?> recordClass;

        public PacketAndRecordClass() {
            packetData = null;
            recordClass = null;
        }

        public PacketAndRecordClass(byte[] data, Class<?> c) {
            packetData = data;
            recordClass = c;
        }
    }

    // Construct an MdnsResponse with the specified data packets applied.
    private MdnsResponse makeMdnsResponse(long time, List<PacketAndRecordClass> responseList)
            throws IOException {
        MdnsResponse response = new MdnsResponse(time, INTERFACE_INDEX, mNetwork);
        for (PacketAndRecordClass responseData : responseList) {
            DatagramPacket packet =
                    new DatagramPacket(responseData.packetData, responseData.packetData.length);
            MdnsPacketReader reader = new MdnsPacketReader(packet);
            String[] name = reader.readLabels();
            reader.skip(2); // skip record type indication.
            // Apply the right kind of record to the response.
            if (responseData.recordClass == MdnsInet4AddressRecord.class) {
                response.setInet4AddressRecord(new MdnsInet4AddressRecord(name, reader));
            } else if (responseData.recordClass == MdnsInet6AddressRecord.class) {
                response.setInet6AddressRecord(new MdnsInet6AddressRecord(name, reader));
            } else if (responseData.recordClass == MdnsPointerRecord.class) {
                response.addPointerRecord(new MdnsPointerRecord(name, reader));
            } else if (responseData.recordClass == MdnsServiceRecord.class) {
                response.setServiceRecord(new MdnsServiceRecord(name, reader));
            } else if (responseData.recordClass == MdnsTextRecord.class) {
                response.setTextRecord(new MdnsTextRecord(name, reader));
            } else {
                fail("Unsupported/unexpected MdnsRecord subtype used in test - invalid test!");
            }
        }
        return response;
    }

    private MdnsResponse makeCompleteResponse(int recordsTtlMillis) {
        final MdnsResponse response = new MdnsResponse(/* now= */ 0, INTERFACE_INDEX, mNetwork);
        final String[] hostname = new String[] { "MyHostname" };
        final String[] serviceName = new String[] { "MyService", "_type", "_tcp", "local" };
        final String[] serviceType = new String[] { "_type", "_tcp", "local" };
        response.addPointerRecord(new MdnsPointerRecord(serviceType, 0L /* receiptTimeMillis */,
                false /* cacheFlush */, recordsTtlMillis, serviceName));
        response.setServiceRecord(new MdnsServiceRecord(serviceName, 0L /* receiptTimeMillis */,
                true /* cacheFlush */, recordsTtlMillis, 0 /* servicePriority */,
                0 /* serviceWeight */, 0 /* servicePort */, hostname));
        response.setTextRecord(new MdnsTextRecord(serviceName, 0L /* receiptTimeMillis */,
                true /* cacheFlush */, recordsTtlMillis, emptyList() /* entries */));
        response.setInet4AddressRecord(new MdnsInetAddressRecord(
                hostname, 0L /* receiptTimeMillis */, true /* cacheFlush */,
                recordsTtlMillis, parseNumericAddress("192.0.2.123")));
        response.setInet6AddressRecord(new MdnsInetAddressRecord(
                hostname, 0L /* receiptTimeMillis */, true /* cacheFlush */,
                recordsTtlMillis, parseNumericAddress("2001:db8::123")));
        return response;
    }

    @Test
    public void getInet4AddressRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(dataIn_ipv4_1, dataIn_ipv4_1.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsInetAddressRecord record = new MdnsInetAddressRecord(name, MdnsRecord.TYPE_A, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasInet4AddressRecord());
        assertTrue(response.setInet4AddressRecord(record));
        assertEquals(response.getInet4AddressRecord(), record);
    }

    @Test
    public void getInet6AddressRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(dataIn_ipv6_1, dataIn_ipv6_1.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsInetAddressRecord record =
                new MdnsInetAddressRecord(name, MdnsRecord.TYPE_AAAA, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasInet6AddressRecord());
        assertTrue(response.setInet6AddressRecord(record));
        assertEquals(response.getInet6AddressRecord(), record);
    }

    @Test
    public void getPointerRecords_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(dataIn_ptr_1, dataIn_ptr_1.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsPointerRecord record = new MdnsPointerRecord(name, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasPointerRecords());
        assertTrue(response.addPointerRecord(record));
        List<MdnsPointerRecord> recordList = response.getPointerRecords();
        assertNotNull(recordList);
        assertEquals(1, recordList.size());
        assertEquals(record, recordList.get(0));
    }

    @Test
    public void getServiceRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(dataIn_service_1, dataIn_service_1.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsServiceRecord record = new MdnsServiceRecord(name, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasServiceRecord());
        assertTrue(response.setServiceRecord(record));
        assertEquals(response.getServiceRecord(), record);
    }

    @Test
    public void getTextRecord_returnsAddedRecord() throws IOException {
        DatagramPacket packet = new DatagramPacket(dataIn_text_1, dataIn_text_1.length);
        MdnsPacketReader reader = new MdnsPacketReader(packet);
        String[] name = reader.readLabels();
        reader.skip(2); // skip record type indication.
        MdnsTextRecord record = new MdnsTextRecord(name, reader);
        MdnsResponse response = new MdnsResponse(0, INTERFACE_INDEX, mNetwork);
        assertFalse(response.hasTextRecord());
        assertTrue(response.setTextRecord(record));
        assertEquals(response.getTextRecord(), record);
    }

    @Test
    public void getInterfaceIndex() {
        final MdnsResponse response1 = new MdnsResponse(/* now= */ 0, INTERFACE_INDEX, mNetwork);
        assertEquals(INTERFACE_INDEX, response1.getInterfaceIndex());

        final MdnsResponse response2 =
                new MdnsResponse(/* now= */ 0, 1234 /* interfaceIndex */, mNetwork);
        assertEquals(1234, response2.getInterfaceIndex());
    }

    @Test
    public void testGetNetwork() {
        final MdnsResponse response1 =
                new MdnsResponse(/* now= */ 0, INTERFACE_INDEX, null /* network */);
        assertNull(response1.getNetwork());

        final MdnsResponse response2 =
                new MdnsResponse(/* now= */ 0, 1234 /* interfaceIndex */, mNetwork);
        assertEquals(mNetwork, response2.getNetwork());
    }

    @Test
    public void mergeRecordsFrom_indicates_change_on_ipv4_address() throws IOException {
        MdnsResponse response = makeMdnsResponse(
                0,
                Arrays.asList(
                        new PacketAndRecordClass(dataIn_ipv4_1, MdnsInet4AddressRecord.class)));
        // Now create a new response that updates the address.
        MdnsResponse response2 = makeMdnsResponse(
                100,
                Arrays.asList(
                        new PacketAndRecordClass(dataIn_ipv4_2, MdnsInet4AddressRecord.class)));
        assertTrue(response.mergeRecordsFrom(response2));
    }

    @Test
    public void mergeRecordsFrom_indicates_change_on_ipv6_address() throws IOException {
        MdnsResponse response = makeMdnsResponse(
                0,
                Arrays.asList(
                        new PacketAndRecordClass(dataIn_ipv6_1, MdnsInet6AddressRecord.class)));
        // Now create a new response that updates the address.
        MdnsResponse response2 = makeMdnsResponse(
                100,
                Arrays.asList(
                        new PacketAndRecordClass(dataIn_ipv6_2, MdnsInet6AddressRecord.class)));
        assertTrue(response.mergeRecordsFrom(response2));
    }

    @Test
    public void mergeRecordsFrom_indicates_change_on_text() throws IOException {
        MdnsResponse response = makeMdnsResponse(
                0,
                Arrays.asList(new PacketAndRecordClass(dataIn_text_1, MdnsTextRecord.class)));
        // Now create a new response that updates the address.
        MdnsResponse response2 = makeMdnsResponse(
                100,
                Arrays.asList(new PacketAndRecordClass(dataIn_text_2, MdnsTextRecord.class)));
        assertTrue(response.mergeRecordsFrom(response2));
    }

    @Test
    public void mergeRecordsFrom_indicates_change_on_service() throws IOException {
        MdnsResponse response = makeMdnsResponse(
                0,
                Arrays.asList(new PacketAndRecordClass(dataIn_service_1, MdnsServiceRecord.class)));
        // Now create a new response that updates the address.
        MdnsResponse response2 = makeMdnsResponse(
                100,
                Arrays.asList(new PacketAndRecordClass(dataIn_service_2, MdnsServiceRecord.class)));
        assertTrue(response.mergeRecordsFrom(response2));
    }

    @Test
    public void mergeRecordsFrom_indicates_change_on_pointer() throws IOException {
        MdnsResponse response = makeMdnsResponse(
                0,
                Arrays.asList(new PacketAndRecordClass(dataIn_ptr_1, MdnsPointerRecord.class)));
        // Now create a new response that updates the address.
        MdnsResponse response2 = makeMdnsResponse(
                100,
                Arrays.asList(new PacketAndRecordClass(dataIn_ptr_2, MdnsPointerRecord.class)));
        assertTrue(response.mergeRecordsFrom(response2));
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void mergeRecordsFrom_indicates_noChange() throws IOException {
        //MdnsConfigsFlagsImpl.useReducedMergeRecordUpdateEvents.override(true);
        List<PacketAndRecordClass> recordList =
                Arrays.asList(
                        new PacketAndRecordClass(dataIn_ipv4_1, MdnsInet4AddressRecord.class),
                        new PacketAndRecordClass(dataIn_ipv6_1, MdnsInet6AddressRecord.class),
                        new PacketAndRecordClass(dataIn_ptr_1, MdnsPointerRecord.class),
                        new PacketAndRecordClass(dataIn_service_2, MdnsServiceRecord.class),
                        new PacketAndRecordClass(dataIn_text_1, MdnsTextRecord.class));
        // Create a two identical responses.
        MdnsResponse response = makeMdnsResponse(0, recordList);
        MdnsResponse response2 = makeMdnsResponse(100, recordList);
        // Merging should not indicate any change.
        assertFalse(response.mergeRecordsFrom(response2));
    }

    @Test
    public void copyConstructor() {
        final MdnsResponse response = makeCompleteResponse(TEST_TTL_MS);
        final MdnsResponse copy = new MdnsResponse(response);

        assertEquals(response.getInet6AddressRecord(), copy.getInet6AddressRecord());
        assertEquals(response.getInet4AddressRecord(), copy.getInet4AddressRecord());
        assertEquals(response.getPointerRecords(), copy.getPointerRecords());
        assertEquals(response.getServiceRecord(), copy.getServiceRecord());
        assertEquals(response.getTextRecord(), copy.getTextRecord());
        assertEquals(response.getRecords(), copy.getRecords());
        assertEquals(response.getNetwork(), copy.getNetwork());
        assertEquals(response.getInterfaceIndex(), copy.getInterfaceIndex());
    }

    @Test
    public void addRecords_noChange() {
        final MdnsResponse response = makeCompleteResponse(TEST_TTL_MS);

        assertFalse(response.addPointerRecord(response.getPointerRecords().get(0)));
        assertFalse(response.setInet6AddressRecord(response.getInet6AddressRecord()));
        assertFalse(response.setInet4AddressRecord(response.getInet4AddressRecord()));
        assertFalse(response.setServiceRecord(response.getServiceRecord()));
        assertFalse(response.setTextRecord(response.getTextRecord()));
    }

    @Test
    public void addRecords_ttlChange() {
        final MdnsResponse response = makeCompleteResponse(TEST_TTL_MS);
        final MdnsResponse ttlZeroResponse = makeCompleteResponse(0);

        assertTrue(response.addPointerRecord(ttlZeroResponse.getPointerRecords().get(0)));
        assertEquals(1, response.getPointerRecords().size());
        assertEquals(0, response.getPointerRecords().get(0).getTtl());
        assertTrue(response.getRecords().stream().anyMatch(r ->
                r == response.getPointerRecords().get(0)));

        assertTrue(response.setInet6AddressRecord(ttlZeroResponse.getInet6AddressRecord()));
        assertEquals(0, response.getInet6AddressRecord().getTtl());
        assertTrue(response.getRecords().stream().anyMatch(r ->
                r == response.getInet6AddressRecord()));

        assertTrue(response.setInet4AddressRecord(ttlZeroResponse.getInet4AddressRecord()));
        assertEquals(0, response.getInet4AddressRecord().getTtl());
        assertTrue(response.getRecords().stream().anyMatch(r ->
                r == response.getInet4AddressRecord()));

        assertTrue(response.setServiceRecord(ttlZeroResponse.getServiceRecord()));
        assertEquals(0, response.getServiceRecord().getTtl());
        assertTrue(response.getRecords().stream().anyMatch(r ->
                r == response.getServiceRecord()));

        assertTrue(response.setTextRecord(ttlZeroResponse.getTextRecord()));
        assertEquals(0, response.getTextRecord().getTtl());
        assertTrue(response.getRecords().stream().anyMatch(r ->
                r == response.getTextRecord()));

        // All records were replaced, not added
        assertEquals(ttlZeroResponse.getRecords().size(), response.getRecords().size());
    }
}