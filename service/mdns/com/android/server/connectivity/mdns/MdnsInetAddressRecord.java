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

package com.android.server.connectivity.mdns;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/** An mDNS "AAAA" or "A" record, which holds an IPv6 or IPv4 address. */
// TODO(b/242631897): Resolve nullness suppression.
@SuppressWarnings("nullness")
@VisibleForTesting
public class MdnsInetAddressRecord extends MdnsRecord {
    private Inet6Address inet6Address;
    private Inet4Address inet4Address;

    /**
     * Constructs the {@link MdnsRecord}
     *
     * @param name   the service host name
     * @param type   the type of record (either Type 'AAAA' or Type 'A')
     * @param reader the reader to read the record from.
     */
    public MdnsInetAddressRecord(String[] name, int type, MdnsPacketReader reader)
            throws IOException {
        super(name, type, reader);
    }

    /** Returns the IPv6 address. */
    public Inet6Address getInet6Address() {
        return inet6Address;
    }

    /** Returns the IPv4 address. */
    public Inet4Address getInet4Address() {
        return inet4Address;
    }

    @Override
    protected void readData(MdnsPacketReader reader) throws IOException {
        int size = 4;
        if (super.getType() == MdnsRecord.TYPE_AAAA) {
            size = 16;
        }
        byte[] buf = new byte[size];
        reader.readBytes(buf);
        try {
            InetAddress address = InetAddress.getByAddress(buf);
            if (address instanceof Inet4Address) {
                inet4Address = (Inet4Address) address;
                inet6Address = null;
            } else if (address instanceof Inet6Address) {
                inet4Address = null;
                inet6Address = (Inet6Address) address;
            } else {
                inet4Address = null;
                inet6Address = null;
            }
        } catch (UnknownHostException e) {
            // Ignore exception
        }
    }

    @Override
    protected void writeData(MdnsPacketWriter writer) throws IOException {
        byte[] buf = null;
        if (inet4Address != null) {
            buf = inet4Address.getAddress();
        } else if (inet6Address != null) {
            buf = inet6Address.getAddress();
        }
        if (buf != null) {
            writer.writeBytes(buf);
        }
    }

    @Override
    public String toString() {
        String type = "AAAA";
        if (super.getType() == MdnsRecord.TYPE_A) {
            type = "A";
        }
        return String.format(
                Locale.ROOT, "%s: Inet4Address: %s Inet6Address: %s", type, inet4Address,
                inet6Address);
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31)
                + Objects.hashCode(inet4Address)
                + Objects.hashCode(inet6Address);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MdnsInetAddressRecord)) {
            return false;
        }

        return super.equals(other)
                && Objects.equals(inet4Address, ((MdnsInetAddressRecord) other).inet4Address)
                && Objects.equals(inet6Address, ((MdnsInetAddressRecord) other).inet6Address);
    }
}