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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.net.module.util.ByteUtils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A class representing a discovered mDNS service instance.
 *
 * @hide
 */
public class MdnsServiceInfo implements Parcelable {
    private static final Charset US_ASCII = Charset.forName("us-ascii");
    private static final Charset UTF_8 = Charset.forName("utf-8");

    /** @hide */
    public static final Parcelable.Creator<MdnsServiceInfo> CREATOR =
            new Parcelable.Creator<MdnsServiceInfo>() {

                @Override
                public MdnsServiceInfo createFromParcel(Parcel source) {
                    return new MdnsServiceInfo(
                            source.readString(),
                            source.createStringArray(),
                            source.createStringArrayList(),
                            source.createStringArray(),
                            source.readInt(),
                            source.readString(),
                            source.readString(),
                            source.createStringArrayList(),
                            source.createTypedArrayList(TextEntry.CREATOR),
                            source.readInt());
                }

                @Override
                public MdnsServiceInfo[] newArray(int size) {
                    return new MdnsServiceInfo[size];
                }
            };

    private final String serviceInstanceName;
    private final String[] serviceType;
    private final List<String> subtypes;
    private final String[] hostName;
    private final int port;
    private final String ipv4Address;
    private final String ipv6Address;
    final List<String> textStrings;
    @Nullable
    final List<TextEntry> textEntries;
    private final int interfaceIndex;

    private final Map<String, byte[]> attributes;

    /** Constructs a {@link MdnsServiceInfo} object with default values. */
    public MdnsServiceInfo(
            String serviceInstanceName,
            String[] serviceType,
            List<String> subtypes,
            String[] hostName,
            int port,
            String ipv4Address,
            String ipv6Address,
            List<String> textStrings) {
        this(
                serviceInstanceName,
                serviceType,
                subtypes,
                hostName,
                port,
                ipv4Address,
                ipv6Address,
                textStrings,
                /* textEntries= */ null,
                /* interfaceIndex= */ -1);
    }

    /** Constructs a {@link MdnsServiceInfo} object with default values. */
    public MdnsServiceInfo(
            String serviceInstanceName,
            String[] serviceType,
            List<String> subtypes,
            String[] hostName,
            int port,
            String ipv4Address,
            String ipv6Address,
            List<String> textStrings,
            @Nullable List<TextEntry> textEntries) {
        this(
                serviceInstanceName,
                serviceType,
                subtypes,
                hostName,
                port,
                ipv4Address,
                ipv6Address,
                textStrings,
                textEntries,
                /* interfaceIndex= */ -1);
    }

    /**
     * Constructs a {@link MdnsServiceInfo} object with default values.
     *
     * @hide
     */
    public MdnsServiceInfo(
            String serviceInstanceName,
            String[] serviceType,
            List<String> subtypes,
            String[] hostName,
            int port,
            String ipv4Address,
            String ipv6Address,
            List<String> textStrings,
            @Nullable List<TextEntry> textEntries,
            int interfaceIndex) {
        this.serviceInstanceName = serviceInstanceName;
        this.serviceType = serviceType;
        this.subtypes = new ArrayList<>();
        if (subtypes != null) {
            this.subtypes.addAll(subtypes);
        }
        this.hostName = hostName;
        this.port = port;
        this.ipv4Address = ipv4Address;
        this.ipv6Address = ipv6Address;
        this.textStrings = new ArrayList<>();
        if (textStrings != null) {
            this.textStrings.addAll(textStrings);
        }
        this.textEntries = (textEntries == null) ? null : new ArrayList<>(textEntries);

        // The module side sends both {@code textStrings} and {@code textEntries} for backward
        // compatibility. We should prefer only {@code textEntries} if it's not null.
        List<TextEntry> entries =
                (this.textEntries != null) ? this.textEntries : parseTextStrings(this.textStrings);
        Map<String, byte[]> attributes = new HashMap<>(entries.size());
        for (TextEntry entry : entries) {
            String key = entry.getKey().toLowerCase(Locale.ENGLISH);

            // Per https://datatracker.ietf.org/doc/html/rfc6763#section-6.4, only the first entry
            // of the same key should be accepted:
            // If a client receives a TXT record containing the same key more than once, then the
            // client MUST silently ignore all but the first occurrence of that attribute.
            if (!attributes.containsKey(key)) {
                attributes.put(key, entry.getValue());
            }
        }
        this.attributes = Collections.unmodifiableMap(attributes);
        this.interfaceIndex = interfaceIndex;
    }

    private static List<TextEntry> parseTextStrings(List<String> textStrings) {
        List<TextEntry> list = new ArrayList(textStrings.size());
        for (String textString : textStrings) {
            TextEntry entry = TextEntry.fromString(textString);
            if (entry != null) {
                list.add(entry);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** @return the name of this service instance. */
    public String getServiceInstanceName() {
        return serviceInstanceName;
    }

    /** @return the type of this service instance. */
    public String[] getServiceType() {
        return serviceType;
    }

    /** @return the list of subtypes supported by this service instance. */
    public List<String> getSubtypes() {
        return new ArrayList<>(subtypes);
    }

    /**
     * @return {@code true} if this service instance supports any subtypes.
     * @return {@code false} if this service instance does not support any subtypes.
     */
    public boolean hasSubtypes() {
        return !subtypes.isEmpty();
    }

    /** @return the host name of this service instance. */
    public String[] getHostName() {
        return hostName;
    }

    /** @return the port number of this service instance. */
    public int getPort() {
        return port;
    }

    /** @return the IPV4 address of this service instance. */
    public String getIpv4Address() {
        return ipv4Address;
    }

    /** @return the IPV6 address of this service instance. */
    public String getIpv6Address() {
        return ipv6Address;
    }

    /**
     * Returns the index of the network interface at which this response was received, or -1 if the
     * index is not known.
     */
    public int getInterfaceIndex() {
        return interfaceIndex;
    }

    /**
     * Returns attribute value for {@code key} as a UTF-8 string. It's the caller who must make sure
     * that the value of {@code key} is indeed a UTF-8 string. {@code null} will be returned if no
     * attribute value exists for {@code key}.
     */
    @Nullable
    public String getAttributeByKey(@NonNull String key) {
        byte[] value = getAttributeAsBytes(key);
        if (value == null) {
            return null;
        }
        return new String(value, UTF_8);
    }

    /**
     * Returns the attribute value for {@code key} as a byte array. {@code null} will be returned if
     * no attribute value exists for {@code key}.
     */
    @Nullable
    public byte[] getAttributeAsBytes(@NonNull String key) {
        return attributes.get(key.toLowerCase(Locale.ENGLISH));
    }

    /** @return an immutable map of all attributes. */
    public Map<String, String> getAttributes() {
        Map<String, String> map = new HashMap<>(attributes.size());
        for (Map.Entry<String, byte[]> kv : attributes.entrySet()) {
            map.put(kv.getKey(), new String(kv.getValue(), UTF_8));
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(serviceInstanceName);
        out.writeStringArray(serviceType);
        out.writeStringList(subtypes);
        out.writeStringArray(hostName);
        out.writeInt(port);
        out.writeString(ipv4Address);
        out.writeString(ipv6Address);
        out.writeStringList(textStrings);
        out.writeTypedList(textEntries);
        out.writeInt(interfaceIndex);
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "Name: %s, subtypes: %s, ip: %s, port: %d",
                serviceInstanceName,
                TextUtils.join(",", subtypes),
                ipv4Address,
                port);
    }


    /** Represents a DNS TXT key-value pair defined by RFC 6763. */
    public static final class TextEntry implements Parcelable {
        public static final Parcelable.Creator<TextEntry> CREATOR =
                new Parcelable.Creator<TextEntry>() {
                    @Override
                    public TextEntry createFromParcel(Parcel source) {
                        return new TextEntry(source);
                    }

                    @Override
                    public TextEntry[] newArray(int size) {
                        return new TextEntry[size];
                    }
                };

        private final String key;
        private final byte[] value;

        /** Creates a new {@link TextEntry} instance from a '=' separated string. */
        @Nullable
        public static TextEntry fromString(String textString) {
            return fromBytes(textString.getBytes(UTF_8));
        }

        /** Creates a new {@link TextEntry} instance from a '=' separated byte array. */
        @Nullable
        public static TextEntry fromBytes(byte[] textBytes) {
            int delimitPos = ByteUtils.indexOf(textBytes, (byte) '=');

            // Per https://datatracker.ietf.org/doc/html/rfc6763#section-6.4:
            // 1. The key MUST be at least one character.  DNS-SD TXT record strings
            // beginning with an '=' character (i.e., the key is missing) MUST be
            // silently ignored.
            // 2. If there is no '=' in a DNS-SD TXT record string, then it is a
            // boolean attribute, simply identified as being present, with no value.
            if (delimitPos < 0) {
                return new TextEntry(new String(textBytes, US_ASCII), "");
            } else if (delimitPos == 0) {
                return null;
            }
            return new TextEntry(
                    new String(Arrays.copyOf(textBytes, delimitPos), US_ASCII),
                    Arrays.copyOfRange(textBytes, delimitPos + 1, textBytes.length));
        }

        /** Creates a new {@link TextEntry} with given key and value of a UTF-8 string. */
        public TextEntry(String key, String value) {
            this(key, value.getBytes(UTF_8));
        }

        /** Creates a new {@link TextEntry} with given key and value of a byte array. */
        public TextEntry(String key, byte[] value) {
            this.key = key;
            this.value = value.clone();
        }

        private TextEntry(Parcel in) {
            key = in.readString();
            value = in.createByteArray();
        }

        public String getKey() {
            return key;
        }

        public byte[] getValue() {
            return value.clone();
        }

        /** Converts this {@link TextEntry} instance to '=' separated byte array. */
        public byte[] toBytes() {
            return ByteUtils.concat(key.getBytes(US_ASCII), new byte[]{'='}, value);
        }

        /** Converts this {@link TextEntry} instance to '=' separated string. */
        @Override
        public String toString() {
            return key + "=" + new String(value, UTF_8);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            } else if (!(other instanceof TextEntry)) {
                return false;
            }
            TextEntry otherEntry = (TextEntry) other;

            return key.equals(otherEntry.key) && Arrays.equals(value, otherEntry.value);
        }

        @Override
        public int hashCode() {
            return 31 * key.hashCode() + Arrays.hashCode(value);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeString(key);
            out.writeByteArray(value);
        }
    }
}