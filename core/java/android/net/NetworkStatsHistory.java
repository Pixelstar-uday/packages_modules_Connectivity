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

package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.CharArrayWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.Arrays;
import java.util.Random;

/**
 * Collection of historical network statistics, recorded into equally-sized
 * "buckets" in time. Internally it stores data in {@code long} series for more
 * efficient persistence.
 * <p>
 * Each bucket is defined by a {@link #bucketStart} timestamp, and lasts for
 * {@link #bucketDuration}. Internally assumes that {@link #bucketStart} is
 * sorted at all times.
 *
 * @hide
 */
public class NetworkStatsHistory implements Parcelable {
    private static final int VERSION_INIT = 1;

    // TODO: teach about varint encoding to use less disk space
    // TODO: extend to record rxPackets/txPackets

    private final long bucketDuration;
    private int bucketCount;
    private long[] bucketStart;
    private long[] rxBytes;
    private long[] txBytes;

    public static class Entry {
        public long bucketStart;
        public long bucketDuration;
        public long rxBytes;
        public long txBytes;
    }

    public NetworkStatsHistory(long bucketDuration) {
        this(bucketDuration, 10);
    }

    public NetworkStatsHistory(long bucketDuration, int initialSize) {
        this.bucketDuration = bucketDuration;
        bucketStart = new long[initialSize];
        rxBytes = new long[initialSize];
        txBytes = new long[initialSize];
        bucketCount = 0;
    }

    public NetworkStatsHistory(Parcel in) {
        bucketDuration = in.readLong();
        bucketStart = readLongArray(in);
        rxBytes = in.createLongArray();
        txBytes = in.createLongArray();
        bucketCount = bucketStart.length;
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(bucketDuration);
        writeLongArray(out, bucketStart, bucketCount);
        writeLongArray(out, rxBytes, bucketCount);
        writeLongArray(out, txBytes, bucketCount);
    }

    public NetworkStatsHistory(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_INIT: {
                bucketDuration = in.readLong();
                bucketStart = readLongArray(in);
                rxBytes = readLongArray(in);
                txBytes = readLongArray(in);
                bucketCount = bucketStart.length;
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_INIT);
        out.writeLong(bucketDuration);
        writeLongArray(out, bucketStart, bucketCount);
        writeLongArray(out, rxBytes, bucketCount);
        writeLongArray(out, txBytes, bucketCount);
    }

    /** {@inheritDoc} */
    public int describeContents() {
        return 0;
    }

    public int size() {
        return bucketCount;
    }

    public long getBucketDuration() {
        return bucketDuration;
    }

    public long getStart() {
        if (bucketCount > 0) {
            return bucketStart[0];
        } else {
            return Long.MAX_VALUE;
        }
    }

    public long getEnd() {
        if (bucketCount > 0) {
            return bucketStart[bucketCount - 1] + bucketDuration;
        } else {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Return specific stats entry.
     */
    public Entry getValues(int i, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.bucketStart = bucketStart[i];
        entry.bucketDuration = bucketDuration;
        entry.rxBytes = rxBytes[i];
        entry.txBytes = txBytes[i];
        return entry;
    }

    /**
     * Record that data traffic occurred in the given time range. Will
     * distribute across internal buckets, creating new buckets as needed.
     */
    public void recordData(long start, long end, long rx, long tx) {
        if (rx < 0 || tx < 0) {
            throw new IllegalArgumentException(
                    "tried recording negative data: rx=" + rx + ", tx=" + tx);
        }

        // create any buckets needed by this range
        ensureBuckets(start, end);

        // distribute data usage into buckets
        final long duration = end - start;
        for (int i = bucketCount - 1; i >= 0; i--) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // bucket is older than record; we're finished
            if (curEnd < start) break;
            // bucket is newer than record; keep looking
            if (curStart > end) continue;

            final long overlap = Math.min(curEnd, end) - Math.max(curStart, start);
            if (overlap > 0) {
                this.rxBytes[i] += rx * overlap / duration;
                this.txBytes[i] += tx * overlap / duration;
            }
        }
    }

    /**
     * Record an entire {@link NetworkStatsHistory} into this history. Usually
     * for combining together stats for external reporting.
     */
    public void recordEntireHistory(NetworkStatsHistory input) {
        for (int i = 0; i < input.bucketCount; i++) {
            final long start = input.bucketStart[i];
            final long end = start + input.bucketDuration;
            recordData(start, end, input.rxBytes[i], input.txBytes[i]);
        }
    }

    /**
     * Ensure that buckets exist for given time range, creating as needed.
     */
    private void ensureBuckets(long start, long end) {
        // normalize incoming range to bucket boundaries
        start -= start % bucketDuration;
        end += (bucketDuration - (end % bucketDuration)) % bucketDuration;

        for (long now = start; now < end; now += bucketDuration) {
            // try finding existing bucket
            final int index = Arrays.binarySearch(bucketStart, 0, bucketCount, now);
            if (index < 0) {
                // bucket missing, create and insert
                insertBucket(~index, now);
            }
        }
    }

    /**
     * Insert new bucket at requested index and starting time.
     */
    private void insertBucket(int index, long start) {
        // create more buckets when needed
        if (bucketCount >= bucketStart.length) {
            final int newLength = Math.max(bucketStart.length, 10) * 3 / 2;
            bucketStart = Arrays.copyOf(bucketStart, newLength);
            rxBytes = Arrays.copyOf(rxBytes, newLength);
            txBytes = Arrays.copyOf(txBytes, newLength);
        }

        // create gap when inserting bucket in middle
        if (index < bucketCount) {
            final int dstPos = index + 1;
            final int length = bucketCount - index;

            System.arraycopy(bucketStart, index, bucketStart, dstPos, length);
            System.arraycopy(rxBytes, index, rxBytes, dstPos, length);
            System.arraycopy(txBytes, index, txBytes, dstPos, length);
        }

        bucketStart[index] = start;
        rxBytes[index] = 0;
        txBytes[index] = 0;
        bucketCount++;
    }

    /**
     * Remove buckets older than requested cutoff.
     */
    public void removeBucketsBefore(long cutoff) {
        int i;
        for (i = 0; i < bucketCount; i++) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // cutoff happens before or during this bucket; everything before
            // this bucket should be removed.
            if (curEnd > cutoff) break;
        }

        if (i > 0) {
            final int length = bucketStart.length;
            bucketStart = Arrays.copyOfRange(bucketStart, i, length);
            rxBytes = Arrays.copyOfRange(rxBytes, i, length);
            txBytes = Arrays.copyOfRange(txBytes, i, length);
            bucketCount -= i;
        }
    }

    /**
     * Return interpolated data usage across the requested range. Interpolates
     * across buckets, so values may be rounded slightly.
     */
    public Entry getValues(long start, long end, Entry recycle) {
        return getValues(start, end, Long.MAX_VALUE, recycle);
    }

    /**
     * Return interpolated data usage across the requested range. Interpolates
     * across buckets, so values may be rounded slightly.
     */
    public Entry getValues(long start, long end, long now, Entry recycle) {
        final Entry entry = recycle != null ? recycle : new Entry();
        entry.bucketStart = start;
        entry.bucketDuration = end - start;
        entry.rxBytes = 0;
        entry.txBytes = 0;

        for (int i = bucketCount - 1; i >= 0; i--) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // bucket is older than record; we're finished
            if (curEnd < start) break;
            // bucket is newer than record; keep looking
            if (curStart > end) continue;

            // include full value for active buckets, otherwise only fractional
            final boolean activeBucket = curStart < now && curEnd > now;
            final long overlap = Math.min(curEnd, end) - Math.max(curStart, start);
            if (activeBucket || overlap == bucketDuration) {
                entry.rxBytes += rxBytes[i];
                entry.txBytes += txBytes[i];
            } else if (overlap > 0) {
                entry.rxBytes += rxBytes[i] * overlap / bucketDuration;
                entry.txBytes += txBytes[i] * overlap / bucketDuration;
            }
        }

        return entry;
    }

    /**
     * @deprecated only for temporary testing
     */
    @Deprecated
    public void generateRandom(long start, long end, long rx, long tx) {
        ensureBuckets(start, end);

        final Random r = new Random();
        while (rx > 1024 && tx > 1024) {
            final long curStart = randomLong(r, start, end);
            final long curEnd = randomLong(r, curStart, end);
            final long curRx = randomLong(r, 0, rx);
            final long curTx = randomLong(r, 0, tx);

            recordData(curStart, curEnd, curRx, curTx);

            rx -= curRx;
            tx -= curTx;
        }
    }

    private static long randomLong(Random r, long start, long end) {
        return (long) (start + (r.nextFloat() * (end - start)));
    }

    public void dump(String prefix, PrintWriter pw, boolean fullHistory) {
        pw.print(prefix);
        pw.print("NetworkStatsHistory: bucketDuration="); pw.println(bucketDuration);

        final int start = fullHistory ? 0 : Math.max(0, bucketCount - 32);
        if (start > 0) {
            pw.print(prefix);
            pw.print("  (omitting "); pw.print(start); pw.println(" buckets)");
        }

        for (int i = start; i < bucketCount; i++) {
            pw.print(prefix);
            pw.print("  bucketStart="); pw.print(bucketStart[i]);
            pw.print(" rxBytes="); pw.print(rxBytes[i]);
            pw.print(" txBytes="); pw.println(txBytes[i]);
        }
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump("", new PrintWriter(writer), false);
        return writer.toString();
    }

    public static final Creator<NetworkStatsHistory> CREATOR = new Creator<NetworkStatsHistory>() {
        public NetworkStatsHistory createFromParcel(Parcel in) {
            return new NetworkStatsHistory(in);
        }

        public NetworkStatsHistory[] newArray(int size) {
            return new NetworkStatsHistory[size];
        }
    };

    private static long[] readLongArray(DataInputStream in) throws IOException {
        final int size = in.readInt();
        final long[] values = new long[size];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    private static void writeLongArray(DataOutputStream out, long[] values, int size) throws IOException {
        if (size > values.length) {
            throw new IllegalArgumentException("size larger than length");
        }
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeLong(values[i]);
        }
    }

    private static long[] readLongArray(Parcel in) {
        final int size = in.readInt();
        final long[] values = new long[size];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    private static void writeLongArray(Parcel out, long[] values, int size) {
        if (size > values.length) {
            throw new IllegalArgumentException("size larger than length");
        }
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeLong(values[i]);
        }
    }

}
