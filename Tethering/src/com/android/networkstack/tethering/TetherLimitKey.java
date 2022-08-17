/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

/** The key of BpfMap which is used for tethering per-interface limit. */
public class TetherLimitKey extends Struct {
    @Field(order = 0, type = Type.S32)
    public final int ifindex;  // upstream interface index

    public TetherLimitKey(final int ifindex) {
        this.ifindex = ifindex;
    }

    // TODO: remove equals, hashCode and toString once aosp/1536721 is merged.
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof TetherLimitKey)) return false;

        final TetherLimitKey that = (TetherLimitKey) obj;

        return ifindex == that.ifindex;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(ifindex);
    }

    @Override
    public String toString() {
        return String.format("ifindex: %d", ifindex);
    }
}
