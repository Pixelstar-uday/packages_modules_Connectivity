// Copyright (C) 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once
#include <netinet/in.h>
#include <netinet/in6.h>

namespace android {
namespace net {
namespace clat {

void makeChecksumNeutral(in6_addr* v6, const in_addr v4, const in6_addr& nat64Prefix);
int generateIpv6Address(const char* iface, const in_addr v4, const in6_addr& nat64Prefix,
                        in6_addr* v6);

}  // namespace clat
}  // namespace net
}  // namespace android
