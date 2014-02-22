/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "NetworkStats"

#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <ScopedUtfChars.h>
#include <ScopedLocalRef.h>
#include <ScopedPrimitiveArray.h>

#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/Vector.h>

namespace android {

static jclass gStringClass;

static struct {
    jfieldID size;
    jfieldID capacity;
    jfieldID iface;
    jfieldID uid;
    jfieldID set;
    jfieldID tag;
    jfieldID rxBytes;
    jfieldID rxPackets;
    jfieldID txBytes;
    jfieldID txPackets;
    jfieldID operations;
} gNetworkStatsClassInfo;

struct stats_line {
    char iface[32];
    int32_t uid;
    int32_t set;
    int32_t tag;
    int64_t rxBytes;
    int64_t rxPackets;
    int64_t txBytes;
    int64_t txPackets;
};

static jobjectArray get_string_array(JNIEnv* env, jobject obj, jfieldID field, int size, bool grow)
{
    if (!grow) {
        jobjectArray array = (jobjectArray)env->GetObjectField(obj, field);
        if (array != NULL) {
            return array;
        }
    }
    return env->NewObjectArray(size, gStringClass, NULL);
}

static jintArray get_int_array(JNIEnv* env, jobject obj, jfieldID field, int size, bool grow)
{
    if (!grow) {
        jintArray array = (jintArray)env->GetObjectField(obj, field);
        if (array != NULL) {
            return array;
        }
    }
    return env->NewIntArray(size);
}

static jlongArray get_long_array(JNIEnv* env, jobject obj, jfieldID field, int size, bool grow)
{
    if (!grow) {
        jlongArray array = (jlongArray)env->GetObjectField(obj, field);
        if (array != NULL) {
            return array;
        }
    }
    return env->NewLongArray(size);
}

static int readNetworkStatsDetail(JNIEnv* env, jclass clazz, jobject stats,
        jstring path, jint limitUid, jobjectArray limitIfacesObj, jint limitTag) {
    ScopedUtfChars path8(env, path);
    if (path8.c_str() == NULL) {
        return -1;
    }

    FILE *fp = fopen(path8.c_str(), "r");
    if (fp == NULL) {
        return -1;
    }

    Vector<String8> limitIfaces;
    if (limitIfacesObj != NULL && env->GetArrayLength(limitIfacesObj) > 0) {
        int num = env->GetArrayLength(limitIfacesObj);
        limitIfaces.setCapacity(num);
        for (int i=0; i<num; i++) {
            jstring string = (jstring)env->GetObjectArrayElement(limitIfacesObj, i);
            ScopedUtfChars string8(env, string);
            if (string8.c_str() != NULL) {
                limitIfaces.add(String8(string8.c_str()));
            }
        }
    }

    Vector<stats_line> lines;

    int lastIdx = 1;
    int idx;
    char buffer[384];
    while (fgets(buffer, sizeof(buffer), fp) != NULL) {
        stats_line s;
        int64_t rawTag;
        char* pos = buffer;
        char* endPos;
        // First field is the index.
        idx = (int)strtol(pos, &endPos, 10);
        //ALOGI("Index #%d: %s", idx, buffer);
        if (pos == endPos) {
            // Skip lines that don't start with in index.  In particular,
            // this will skip the initial header line.
            continue;
        }
        if (idx != lastIdx + 1) {
            ALOGE("inconsistent idx=%d after lastIdx=%d: %s", idx, lastIdx, buffer);
            fclose(fp);
            return -1;
        }
        lastIdx = idx;
        pos = endPos;
        // Skip whitespace.
        while (*pos == ' ') {
            pos++;
        }
        // Next field is iface.
        int ifaceIdx = 0;
        while (*pos != ' ' && *pos != 0 && ifaceIdx < (int)(sizeof(s.iface)-1)) {
            s.iface[ifaceIdx] = *pos;
            ifaceIdx++;
            pos++;
        }
        if (*pos != ' ') {
            ALOGE("bad iface: %s", buffer);
            fclose(fp);
            return -1;
        }
        s.iface[ifaceIdx] = 0;
        if (limitIfaces.size() > 0) {
            // Is this an iface the caller is interested in?
            int i = 0;
            while (i < (int)limitIfaces.size()) {
                if (limitIfaces[i] == s.iface) {
                    break;
                }
                i++;
            }
            if (i >= (int)limitIfaces.size()) {
                // Nothing matched; skip this line.
                //ALOGI("skipping due to iface: %s", buffer);
                continue;
            }
        }
        // Skip whitespace.
        while (*pos == ' ') {
            pos++;
        }
        // Next field is tag.
        rawTag = strtoll(pos, &endPos, 16);
        //ALOGI("Index #%d: %s", idx, buffer);
        if (pos == endPos) {
            ALOGE("bad tag: %s", pos);
            fclose(fp);
            return -1;
        }
        s.tag = rawTag >> 32;
        if (limitTag != -1 && s.tag != limitTag) {
            //ALOGI("skipping due to tag: %s", buffer);
            continue;
        }
        pos = endPos;
        // Skip whitespace.
        while (*pos == ' ') {
            pos++;
        }
        // Parse remaining fields.
        if (sscanf(pos, "%u %u %llu %llu %llu %llu",
                &s.uid, &s.set, &s.rxBytes, &s.rxPackets,
                &s.txBytes, &s.txPackets) == 6) {
            if (limitUid != -1 && limitUid != s.uid) {
                //ALOGI("skipping due to uid: %s", buffer);
                continue;
            }
            lines.push_back(s);
        } else {
            //ALOGI("skipping due to bad remaining fields: %s", pos);
        }
    }

    if (fclose(fp) != 0) {
        ALOGE("Failed to close netstats file");
        return -1;
    }

    int size = lines.size();
    bool grow = size > env->GetIntField(stats, gNetworkStatsClassInfo.capacity);

    ScopedLocalRef<jobjectArray> iface(env, get_string_array(env, stats,
            gNetworkStatsClassInfo.iface, size, grow));
    if (iface.get() == NULL) return -1;
    ScopedIntArrayRW uid(env, get_int_array(env, stats,
            gNetworkStatsClassInfo.uid, size, grow));
    if (uid.get() == NULL) return -1;
    ScopedIntArrayRW set(env, get_int_array(env, stats,
            gNetworkStatsClassInfo.set, size, grow));
    if (set.get() == NULL) return -1;
    ScopedIntArrayRW tag(env, get_int_array(env, stats,
            gNetworkStatsClassInfo.tag, size, grow));
    if (tag.get() == NULL) return -1;
    ScopedLongArrayRW rxBytes(env, get_long_array(env, stats,
            gNetworkStatsClassInfo.rxBytes, size, grow));
    if (rxBytes.get() == NULL) return -1;
    ScopedLongArrayRW rxPackets(env, get_long_array(env, stats,
            gNetworkStatsClassInfo.rxPackets, size, grow));
    if (rxPackets.get() == NULL) return -1;
    ScopedLongArrayRW txBytes(env, get_long_array(env, stats,
            gNetworkStatsClassInfo.txBytes, size, grow));
    if (txBytes.get() == NULL) return -1;
    ScopedLongArrayRW txPackets(env, get_long_array(env, stats,
            gNetworkStatsClassInfo.txPackets, size, grow));
    if (txPackets.get() == NULL) return -1;
    ScopedLongArrayRW operations(env, get_long_array(env, stats,
            gNetworkStatsClassInfo.operations, size, grow));
    if (operations.get() == NULL) return -1;

    for (int i = 0; i < size; i++) {
        ScopedLocalRef<jstring> ifaceString(env, env->NewStringUTF(lines[i].iface));
        env->SetObjectArrayElement(iface.get(), i, ifaceString.get());

        uid[i] = lines[i].uid;
        set[i] = lines[i].set;
        tag[i] = lines[i].tag;
        rxBytes[i] = lines[i].rxBytes;
        rxPackets[i] = lines[i].rxPackets;
        txBytes[i] = lines[i].txBytes;
        txPackets[i] = lines[i].txPackets;
    }

    env->SetIntField(stats, gNetworkStatsClassInfo.size, size);
    if (grow) {
        env->SetIntField(stats, gNetworkStatsClassInfo.capacity, size);
        env->SetObjectField(stats, gNetworkStatsClassInfo.iface, iface.get());
        env->SetObjectField(stats, gNetworkStatsClassInfo.uid, uid.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.set, set.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.tag, tag.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.rxBytes, rxBytes.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.rxPackets, rxPackets.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.txBytes, txBytes.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.txPackets, txPackets.getJavaArray());
        env->SetObjectField(stats, gNetworkStatsClassInfo.operations, operations.getJavaArray());
    }

    return 0;
}

static jclass findClass(JNIEnv* env, const char* name) {
    ScopedLocalRef<jclass> localClass(env, env->FindClass(name));
    jclass result = reinterpret_cast<jclass>(env->NewGlobalRef(localClass.get()));
    if (result == NULL) {
        ALOGE("failed to find class '%s'", name);
        abort();
    }
    return result;
}

static JNINativeMethod gMethods[] = {
        { "nativeReadNetworkStatsDetail",
                "(Landroid/net/NetworkStats;Ljava/lang/String;I[Ljava/lang/String;I)I",
                (void*) readNetworkStatsDetail }
};

int register_com_android_internal_net_NetworkStatsFactory(JNIEnv* env) {
    int err = AndroidRuntime::registerNativeMethods(env,
            "com/android/internal/net/NetworkStatsFactory", gMethods,
            NELEM(gMethods));

    gStringClass = findClass(env, "java/lang/String");

    jclass clazz = env->FindClass("android/net/NetworkStats");
    gNetworkStatsClassInfo.size = env->GetFieldID(clazz, "size", "I");
    gNetworkStatsClassInfo.capacity = env->GetFieldID(clazz, "capacity", "I");
    gNetworkStatsClassInfo.iface = env->GetFieldID(clazz, "iface", "[Ljava/lang/String;");
    gNetworkStatsClassInfo.uid = env->GetFieldID(clazz, "uid", "[I");
    gNetworkStatsClassInfo.set = env->GetFieldID(clazz, "set", "[I");
    gNetworkStatsClassInfo.tag = env->GetFieldID(clazz, "tag", "[I");
    gNetworkStatsClassInfo.rxBytes = env->GetFieldID(clazz, "rxBytes", "[J");
    gNetworkStatsClassInfo.rxPackets = env->GetFieldID(clazz, "rxPackets", "[J");
    gNetworkStatsClassInfo.txBytes = env->GetFieldID(clazz, "txBytes", "[J");
    gNetworkStatsClassInfo.txPackets = env->GetFieldID(clazz, "txPackets", "[J");
    gNetworkStatsClassInfo.operations = env->GetFieldID(clazz, "operations", "[J");

    return err;
}

}
