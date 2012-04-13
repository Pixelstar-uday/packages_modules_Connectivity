/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.nsd;

import android.os.Parcelable;
import android.os.Parcel;

import java.net.InetAddress;

/**
 * Defines a service based on DNS service discovery
 * {@hide}
 */
public class DnsSdServiceInfo implements NetworkServiceInfo, Parcelable {

    private String mServiceName;

    private String mServiceType;

    private DnsSdTxtRecord mTxtRecord;

    private InetAddress mHost;

    private int mPort;

    public DnsSdServiceInfo() {
    }

    public DnsSdServiceInfo(String sn, String rt, DnsSdTxtRecord tr) {
        mServiceName = sn;
        mServiceType = rt;
        mTxtRecord = tr;
    }

    @Override
    /** @hide */
    public String getServiceName() {
        return mServiceName;
    }

    @Override
    /** @hide */
    public void setServiceName(String s) {
        mServiceName = s;
    }

    @Override
    /** @hide */
    public String getServiceType() {
        return mServiceType;
    }

    @Override
    /** @hide */
    public void setServiceType(String s) {
        mServiceType = s;
    }

    public DnsSdTxtRecord getTxtRecord() {
        return mTxtRecord;
    }

    public void setTxtRecord(DnsSdTxtRecord t) {
        mTxtRecord = new DnsSdTxtRecord(t);
    }

    public InetAddress getHost() {
        return mHost;
    }

    public void setHost(InetAddress s) {
        mHost = s;
    }

    public int getPort() {
        return mPort;
    }

    public void setPort(int p) {
        mPort = p;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("name: ").append(mServiceName).
            append("type: ").append(mServiceType).
            append("host: ").append(mHost).
            append("port: ").append(mPort).
            append("txtRecord: ").append(mTxtRecord);
        return sb.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mServiceType);
        dest.writeParcelable(mTxtRecord, flags);
        if (mHost != null) {
            dest.writeByte((byte)1);
            dest.writeByteArray(mHost.getAddress());
        } else {
            dest.writeByte((byte)0);
        }
        dest.writeInt(mPort);
    }

    /** Implement the Parcelable interface */
    public static final Creator<DnsSdServiceInfo> CREATOR =
        new Creator<DnsSdServiceInfo>() {
            public DnsSdServiceInfo createFromParcel(Parcel in) {
                DnsSdServiceInfo info = new DnsSdServiceInfo();
                info.mServiceName = in.readString();
                info.mServiceType = in.readString();
                info.mTxtRecord = in.readParcelable(null);

                if (in.readByte() == 1) {
                    try {
                        info.mHost = InetAddress.getByAddress(in.createByteArray());
                    } catch (java.net.UnknownHostException e) {}
                }

                info.mPort = in.readInt();
                return info;
            }

            public DnsSdServiceInfo[] newArray(int size) {
                return new DnsSdServiceInfo[size];
            }
        };

}
