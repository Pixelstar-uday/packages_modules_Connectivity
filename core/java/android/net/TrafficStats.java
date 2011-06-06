/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

import android.content.Context;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.ServiceManager;

import dalvik.system.BlockGuard;

import java.net.Socket;
import java.net.SocketException;

/**
 * Class that provides network traffic statistics.  These statistics include
 * bytes transmitted and received and network packets transmitted and received,
 * over all interfaces, over the mobile interface, and on a per-UID basis.
 * <p>
 * These statistics may not be available on all platforms.  If the statistics
 * are not supported by this device, {@link #UNSUPPORTED} will be returned.
 */
public class TrafficStats {
    /**
     * The return value to indicate that the device does not support the statistic.
     */
    public final static int UNSUPPORTED = -1;

    // TODO: find better home for these template constants

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_MOBILE} style
     * networks together. Only uses statistics for currently active IMSI.
     *
     * @hide
     */
    public static final int TEMPLATE_MOBILE_ALL = 1;

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_MOBILE} style
     * networks together that roughly meet a "3G" definition, or lower. Only
     * uses statistics for currently active IMSI.
     *
     * @hide
     */
    public static final int TEMPLATE_MOBILE_3G_LOWER = 2;

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_MOBILE} style
     * networks together that meet a "4G" definition. Only uses statistics for
     * currently active IMSI.
     *
     * @hide
     */
    public static final int TEMPLATE_MOBILE_4G = 3;

    /**
     * Template to combine all {@link ConnectivityManager#TYPE_WIFI} style
     * networks together.
     *
     * @hide
     */
    public static final int TEMPLATE_WIFI = 4;

    /**
     * Snapshot of {@link NetworkStats} when the currently active profiling
     * session started, or {@code null} if no session active.
     *
     * @see #startDataProfiling(Context)
     * @see #stopDataProfiling(Context)
     */
    private static NetworkStats sActiveProfilingStart;

    private static Object sProfilingLock = new Object();

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     */
    public static void setThreadStatsTag(String tag) {
        BlockGuard.setThreadSocketStatsTag(tag);
    }

    public static void clearThreadStatsTag() {
        BlockGuard.setThreadSocketStatsTag(null);
    }

    /**
     * Set specific UID to use when accounting {@link Socket} traffic
     * originating from the current thread. Designed for use when performing an
     * operation on behalf of another application.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     * <p>
     * To take effect, caller must hold
     * {@link android.Manifest.permission#UPDATE_DEVICE_STATS} permission.
     *
     * {@hide}
     */
    public static void setThreadStatsUid(int uid) {
        BlockGuard.setThreadSocketStatsUid(uid);
    }

    /** {@hide} */
    public static void clearThreadStatsUid() {
        BlockGuard.setThreadSocketStatsUid(-1);
    }

    /**
     * Tag the given {@link Socket} with any statistics parameters active for
     * the current thread. Subsequent calls always replace any existing
     * parameters. When finished, call {@link #untagSocket(Socket)} to remove
     * statistics parameters.
     *
     * @see #setThreadStatsTag(String)
     * @see #setThreadStatsUid(int)
     */
    public static void tagSocket(Socket socket) throws SocketException {
        BlockGuard.tagSocketFd(socket.getFileDescriptor$());
    }

    /**
     * Remove any statistics parameters from the given {@link Socket}.
     */
    public static void untagSocket(Socket socket) throws SocketException {
        BlockGuard.untagSocketFd(socket.getFileDescriptor$());
    }

    /**
     * Start profiling data usage for current UID. Only one profiling session
     * can be active at a time.
     *
     * @hide
     */
    public static void startDataProfiling(Context context) {
        synchronized (sProfilingLock) {
            if (sActiveProfilingStart != null) {
                throw new IllegalStateException("already profiling data");
            }

            // take snapshot in time; we calculate delta later
            sActiveProfilingStart = getNetworkStatsForUid(context);
        }
    }

    /**
     * Stop profiling data usage for current UID.
     *
     * @return Detailed {@link NetworkStats} of data that occurred since last
     *         {@link #startDataProfiling(Context)} call.
     * @hide
     */
    public static NetworkStats stopDataProfiling(Context context) {
        synchronized (sProfilingLock) {
            if (sActiveProfilingStart == null) {
                throw new IllegalStateException("not profiling data");
            }

            // subtract starting values and return delta
            final NetworkStats profilingStop = getNetworkStatsForUid(context);
            final NetworkStats profilingDelta = profilingStop.subtractClamped(
                    sActiveProfilingStart);
            sActiveProfilingStart = null;
            return profilingDelta;
        }
    }

    /**
     * Get the total number of packets transmitted through the mobile interface.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getMobileTxPackets();

    /**
     * Get the total number of packets received through the mobile interface.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getMobileRxPackets();

    /**
     * Get the total number of bytes transmitted through the mobile interface.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
      public static native long getMobileTxBytes();

    /**
     * Get the total number of bytes received through the mobile interface.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getMobileRxBytes();

    /**
     * Get the total number of packets transmitted through the specified interface.
     *
     * @return number of packets.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static native long getTxPackets(String iface);

    /**
     * Get the total number of packets received through the specified interface.
     *
     * @return number of packets.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static native long getRxPackets(String iface);

    /**
     * Get the total number of bytes transmitted through the specified interface.
     *
     * @return number of bytes.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static native long getTxBytes(String iface);

    /**
     * Get the total number of bytes received through the specified interface.
     *
     * @return number of bytes.  If the statistics are not supported by this interface,
     * {@link #UNSUPPORTED} will be returned.
     * @hide
     */
    public static native long getRxBytes(String iface);


    /**
     * Get the total number of packets sent through all network interfaces.
     *
     * @return the number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getTotalTxPackets();

    /**
     * Get the total number of packets received through all network interfaces.
     *
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getTotalRxPackets();

    /**
     * Get the total number of bytes sent through all network interfaces.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getTotalTxBytes();

    /**
     * Get the total number of bytes received through all network interfaces.
     *
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getTotalRxBytes();

    /**
     * Get the number of bytes sent through the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTxBytes(int uid);

    /**
     * Get the number of bytes received through the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes
     */
    public static native long getUidRxBytes(int uid);

    /**
     * Get the number of packets (TCP segments + UDP) sent through
     * the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets.
     * If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTxPackets(int uid);

    /**
     * Get the number of packets (TCP segments + UDP) received through
     * the network for this UID.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets
     */
    public static native long getUidRxPackets(int uid);

    /**
     * Get the number of TCP payload bytes sent for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpTxBytes(int uid);

    /**
     * Get the number of TCP payload bytes received for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpRxBytes(int uid);

    /**
     * Get the number of UDP payload bytes sent for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpTxBytes(int uid);

    /**
     * Get the number of UDP payload bytes received for this UID.
     * This total does not include protocol and control overheads at
     * the transport and the lower layers of the networking stack.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of bytes.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpRxBytes(int uid);

    /**
     * Get the number of TCP segments sent for this UID.
     * Does not include TCP control packets (SYN/ACKs/FIN/..).
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of TCP segments.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpTxSegments(int uid);

    /**
     * Get the number of TCP segments received for this UID.
     * Does not include TCP control packets (SYN/ACKs/FIN/..).
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of TCP segments.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidTcpRxSegments(int uid);


    /**
     * Get the number of UDP packets sent for this UID.
     * Includes DNS requests.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpTxPackets(int uid);

    /**
     * Get the number of UDP packets received for this UID.
     * Includes DNS responses.
     * The statistics are across all interfaces.
     *
     * {@see android.os.Process#myUid()}.
     *
     * @param uid The UID of the process to examine.
     * @return number of packets.  If the statistics are not supported by this device,
     * {@link #UNSUPPORTED} will be returned.
     */
    public static native long getUidUdpRxPackets(int uid);

    /**
     * Return detailed {@link NetworkStats} for the current UID. Requires no
     * special permission.
     */
    private static NetworkStats getNetworkStatsForUid(Context context) {
        final IBinder binder = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        final INetworkManagementService service = INetworkManagementService.Stub.asInterface(
                binder);

        final int uid = android.os.Process.myUid();
        try {
            return service.getNetworkStatsUidDetail(uid);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
