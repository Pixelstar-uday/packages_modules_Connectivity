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

package com.android.server.connectivity;

import static android.net.INetd.IF_STATE_UP;
import static android.net.INetd.PERMISSION_SYSTEM;

import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.INetd;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * This coordinator is responsible for providing clat relevant functionality.
 *
 * {@hide}
 */
public class ClatCoordinator {
    private static final String TAG = ClatCoordinator.class.getSimpleName();

    // Sync from external/android-clat/clatd.c
    // 40 bytes IPv6 header - 20 bytes IPv4 header + 8 bytes fragment header.
    @VisibleForTesting
    static final int MTU_DELTA = 28;
    @VisibleForTesting
    static final int CLAT_MAX_MTU = 65536;

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // For historical reasons, start with 192.0.0.4, and after that, use all subsequent addresses
    // in 192.0.0.0/29 (RFC 7335).
    @VisibleForTesting
    static final String INIT_V4ADDR_STRING = "192.0.0.4";
    @VisibleForTesting
    static final int INIT_V4ADDR_PREFIX_LEN = 29;
    private static final InetAddress GOOGLE_DNS_4 = InetAddress.parseNumericAddress("8.8.8.8");

    private static final int INVALID_PID = 0;

    @NonNull
    private final INetd mNetd;
    @NonNull
    private final Dependencies mDeps;
    @Nullable
    private String mIface = null;
    private int mPid = INVALID_PID;

    @VisibleForTesting
    abstract static class Dependencies {
        /**
          * Get netd.
          */
        @NonNull
        public abstract INetd getNetd();

        /**
         * @see ParcelFileDescriptor#adoptFd(int).
         */
        @NonNull
        public ParcelFileDescriptor adoptFd(int fd) {
            return ParcelFileDescriptor.adoptFd(fd);
        }

        /**
         * Create tun interface for a given interface name.
         */
        public int jniCreateTunInterface(@NonNull String tuniface) throws IOException {
            return createTunInterface(tuniface);
        }

        /**
         * Pick an IPv4 address for clat.
         */
        @NonNull
        public String jniSelectIpv4Address(@NonNull String v4addr, int prefixlen)
                throws IOException {
            return selectIpv4Address(v4addr, prefixlen);
        }

        /**
         * Generate a checksum-neutral IID.
         */
        @NonNull
        public String jniGenerateIpv6Address(@NonNull String iface, @NonNull String v4,
                @NonNull String prefix64) throws IOException {
            return generateIpv6Address(iface, v4, prefix64);
        }

        /**
         * Detect MTU.
         */
        public int jniDetectMtu(@NonNull String platSubnet, int platSuffix, int mark)
                throws IOException {
            return detectMtu(platSubnet, platSuffix, mark);
        }
    }

    @VisibleForTesting
    static int getFwmark(int netId) {
        // See union Fwmark in system/netd/include/Fwmark.h
        return (netId & 0xffff)
                | 0x1 << 16  // protectedFromVpn: true
                | 0x1 << 17  // explicitlySelected: true
                | (PERMISSION_SYSTEM & 0x3) << 18;
    }

    @VisibleForTesting
    static int adjustMtu(int mtu) {
        // clamp to minimum ipv6 mtu - this probably cannot ever trigger
        if (mtu < IPV6_MIN_MTU) mtu = IPV6_MIN_MTU;
        // clamp to buffer size
        if (mtu > CLAT_MAX_MTU) mtu = CLAT_MAX_MTU;
        // decrease by ipv6(40) + ipv6 fragmentation header(8) vs ipv4(20) overhead of 28 bytes
        mtu -= MTU_DELTA;

        return mtu;
    }

    public ClatCoordinator(@NonNull Dependencies deps) {
        mDeps = deps;
        mNetd = mDeps.getNetd();
    }

    /**
     * Start clatd for a given interface and NAT64 prefix.
     */
    public String clatStart(final String iface, final int netId,
            @NonNull final IpPrefix nat64Prefix)
            throws IOException {
        if (nat64Prefix.getPrefixLength() != 96) {
            throw new IOException("Prefix must be 96 bits long: " + nat64Prefix);
        }

        // [1] Pick an IPv4 address from 192.0.0.4, 192.0.0.5, 192.0.0.6 ..
        final String v4;
        try {
            v4 = mDeps.jniSelectIpv4Address(INIT_V4ADDR_STRING, INIT_V4ADDR_PREFIX_LEN);
        } catch (IOException e) {
            throw new IOException("no IPv4 addresses were available for clat: " + e);
        }

        // [2] Generate a checksum-neutral IID.
        final String pfx96 = nat64Prefix.getAddress().getHostAddress();
        final String v6;
        try {
            v6 = mDeps.jniGenerateIpv6Address(iface, v4, pfx96);
        } catch (IOException e) {
            throw new IOException("no IPv6 addresses were available for clat: " + e);
        }

        // [3] Open, configure and bring up the tun interface.
        // Create the v4-... tun interface.
        final String tunIface = CLAT_PREFIX + iface;
        final ParcelFileDescriptor tunFd;
        try {
            tunFd = mDeps.adoptFd(mDeps.jniCreateTunInterface(tunIface));
        } catch (IOException e) {
            throw new IOException("Create tun interface " + tunIface + " failed: " + e);
        }

        // disable IPv6 on it - failing to do so is not a critical error
        try {
            mNetd.interfaceSetEnableIPv6(tunIface, false /* enabled */);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Disable IPv6 on " + tunIface + " failed: " + e);
        }

        // Detect ipv4 mtu.
        final Integer fwmark = getFwmark(netId);
        final int detectedMtu = mDeps.jniDetectMtu(pfx96,
                ByteBuffer.wrap(GOOGLE_DNS_4.getAddress()).getInt(), fwmark);
        final int mtu = adjustMtu(detectedMtu);
        Log.i(TAG, "ipv4 mtu is " + mtu);

        // TODO: add setIptablesDropRule

        // Config tun interface mtu, address and bring up.
        try {
            mNetd.interfaceSetMtu(tunIface, mtu);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IOException("Set MTU " + mtu + " on " + tunIface + " failed: " + e);
        }
        final InterfaceConfigurationParcel ifConfig = new InterfaceConfigurationParcel();
        ifConfig.ifName = tunIface;
        ifConfig.ipv4Addr = v4;
        ifConfig.prefixLength = 32;
        ifConfig.hwAddr = "";
        ifConfig.flags = new String[] {IF_STATE_UP};
        try {
            mNetd.interfaceSetCfg(ifConfig);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IOException("Setting IPv4 address to " + ifConfig.ipv4Addr + "/"
                    + ifConfig.prefixLength + " failed on " + ifConfig.ifName + ": " + e);
        }

        // TODO: start clatd and returns local xlat464 v6 address.
        return null;
    }

    private static native String selectIpv4Address(String v4addr, int prefixlen)
            throws IOException;
    private static native String generateIpv6Address(String iface, String v4, String prefix64)
            throws IOException;
    private static native int createTunInterface(String tuniface) throws IOException;
    private static native int detectMtu(String platSubnet, int platSuffix, int mark)
            throws IOException;
}
