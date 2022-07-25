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

#include <linux/bpf.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <linux/ip.h>
#include <linux/ipv6.h>
#include <linux/pkt_cls.h>
#include <linux/tcp.h>
#include <linux/types.h>
#include <netinet/in.h>
#include <netinet/udp.h>
#include <stdint.h>
#include <string.h>

// The resulting .o needs to load on the Android T beta 3 bpfloader
#define BPFLOADER_MIN_VER BPFLOADER_T_BETA3_VERSION

#include "bpf_helpers.h"
#include "dscpPolicy.h"

#define ECN_MASK 3
#define IP4_OFFSET(field, header) (header + offsetof(struct iphdr, field))
#define UPDATE_TOS(dscp, tos) (dscp << 2) | (tos & ECN_MASK)
#define UPDATE_PRIORITY(dscp) ((dscp >> 2) + 0x60)
#define UPDATE_FLOW_LABEL(dscp, flow_lbl) ((dscp & 0xf) << 6) + (flow_lbl >> 6)

DEFINE_BPF_MAP_GRW(switch_comp_map, ARRAY, int, uint64_t, 1, AID_SYSTEM)

DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_A, HASH, uint64_t, RuleEntry, MAX_POLICIES,
                   AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_B, HASH, uint64_t, RuleEntry, MAX_POLICIES,
                   AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_A, HASH, uint64_t, RuleEntry, MAX_POLICIES,
                   AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_B, HASH, uint64_t, RuleEntry, MAX_POLICIES,
                   AID_SYSTEM)

DEFINE_BPF_MAP_GRW(ipv4_dscp_policies_map, ARRAY, uint32_t, DscpPolicy, MAX_POLICIES, AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_dscp_policies_map, ARRAY, uint32_t, DscpPolicy, MAX_POLICIES, AID_SYSTEM)

static inline __always_inline void match_policy(struct __sk_buff* skb, bool ipv4, bool is_eth) {
    void* data = (void*)(long)skb->data;
    const void* data_end = (void*)(long)skb->data_end;

    const int l2_header_size = is_eth ? sizeof(struct ethhdr) : 0;
    struct ethhdr* eth = is_eth ? data : NULL;

    if (data + l2_header_size > data_end) return;

    int zero = 0;
    int hdr_size = 0;
    uint64_t* selected_map = bpf_switch_comp_map_lookup_elem(&zero);

    // use this with HASH map so map lookup only happens once policies have been added?
    if (!selected_map) {
        return;
    }

    // used for map lookup
    uint64_t cookie = bpf_get_socket_cookie(skb);
    if (!cookie) return;

    uint16_t sport = 0;
    uint16_t dport = 0;
    uint8_t protocol = 0;  // TODO: Use are reserved value? Or int (-1) and cast to uint below?
    struct in6_addr src_ip = {};
    struct in6_addr dst_ip = {};
    uint8_t tos = 0;       // Only used for IPv4
    uint8_t priority = 0;  // Only used for IPv6
    uint8_t flow_lbl = 0;  // Only used for IPv6
    if (ipv4) {
        const struct iphdr* const iph = is_eth ? (void*)(eth + 1) : data;
        hdr_size = l2_header_size + sizeof(struct iphdr);
        // Must have ipv4 header
        if (data + hdr_size > data_end) return;

        // IP version must be 4
        if (iph->version != 4) return;

        // We cannot handle IP options, just standard 20 byte == 5 dword minimal IPv4 header
        if (iph->ihl != 5) return;

        // V4 mapped address in in6_addr sets 10/11 position to 0xff.
        src_ip.s6_addr32[2] = htonl(0x0000ffff);
        dst_ip.s6_addr32[2] = htonl(0x0000ffff);

        // Copy IPv4 address into in6_addr for easy comparison below.
        src_ip.s6_addr32[3] = iph->saddr;
        dst_ip.s6_addr32[3] = iph->daddr;
        protocol = iph->protocol;
        tos = iph->tos;
    } else {
        struct ipv6hdr* ip6h = is_eth ? (void*)(eth + 1) : data;
        hdr_size = l2_header_size + sizeof(struct ipv6hdr);
        // Must have ipv6 header
        if (data + hdr_size > data_end) return;

        if (ip6h->version != 6) return;

        src_ip = ip6h->saddr;
        dst_ip = ip6h->daddr;
        protocol = ip6h->nexthdr;
        priority = ip6h->priority;
        flow_lbl = ip6h->flow_lbl[0];
    }

    switch (protocol) {
        case IPPROTO_UDP:
        case IPPROTO_UDPLITE: {
            struct udphdr* udp;
            udp = data + hdr_size;
            if ((void*)(udp + 1) > data_end) return;
            sport = udp->source;
            dport = udp->dest;
        } break;
        case IPPROTO_TCP: {
            struct tcphdr* tcp;
            tcp = data + hdr_size;
            if ((void*)(tcp + 1) > data_end) return;
            sport = tcp->source;
            dport = tcp->dest;
        } break;
        default:
            return;
    }

    RuleEntry* existing_rule;
    if (ipv4) {
        if (*selected_map == MAP_A) {
            existing_rule = bpf_ipv4_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            existing_rule = bpf_ipv4_socket_to_policies_map_B_lookup_elem(&cookie);
        }
    } else {
        if (*selected_map == MAP_A) {
            existing_rule = bpf_ipv6_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            existing_rule = bpf_ipv6_socket_to_policies_map_B_lookup_elem(&cookie);
        }
    }

    if (existing_rule && v6_equal(src_ip, existing_rule->src_ip) &&
            v6_equal(dst_ip, existing_rule->dst_ip) && skb->ifindex == existing_rule->ifindex &&
        ntohs(sport) == htons(existing_rule->src_port) &&
        ntohs(dport) == htons(existing_rule->dst_port) && protocol == existing_rule->proto) {
        if (ipv4) {
            uint8_t newTos = UPDATE_TOS(existing_rule->dscp_val, tos);
            bpf_l3_csum_replace(skb, IP4_OFFSET(check, l2_header_size), htons(tos), htons(newTos),
                                sizeof(uint16_t));
            bpf_skb_store_bytes(skb, IP4_OFFSET(tos, l2_header_size), &newTos, sizeof(newTos), 0);
        } else {
            uint8_t new_priority = UPDATE_PRIORITY(existing_rule->dscp_val);
            uint8_t new_flow_label = UPDATE_FLOW_LABEL(existing_rule->dscp_val, flow_lbl);
            bpf_skb_store_bytes(skb, 0 + l2_header_size, &new_priority, sizeof(uint8_t), 0);
            bpf_skb_store_bytes(skb, 1 + l2_header_size, &new_flow_label, sizeof(uint8_t), 0);
        }
        return;
    }

    // Linear scan ipv4_dscp_policies_map since no stored params match skb.
    int best_score = -1;
    uint32_t best_match = 0;

    for (register uint64_t i = 0; i < MAX_POLICIES; i++) {
        int score = 0;
        uint8_t temp_mask = 0;
        // Using a uint64 in for loop prevents infinite loop during BPF load,
        // but the key is uint32, so convert back.
        uint32_t key = i;

        DscpPolicy* policy;
        if (ipv4) {
            policy = bpf_ipv4_dscp_policies_map_lookup_elem(&key);
        } else {
            policy = bpf_ipv6_dscp_policies_map_lookup_elem(&key);
        }

        // If the policy lookup failed, present_fields is 0, or iface index does not match
        // index on skb buff, then we can continue to next policy.
        if (!policy || policy->present_fields == 0 || policy->ifindex != skb->ifindex) continue;

        if ((policy->present_fields & SRC_IP_MASK_FLAG) == SRC_IP_MASK_FLAG &&
            v6_equal(src_ip, policy->src_ip)) {
            score++;
            temp_mask |= SRC_IP_MASK_FLAG;
        }
        if ((policy->present_fields & DST_IP_MASK_FLAG) == DST_IP_MASK_FLAG &&
            v6_equal(dst_ip, policy->dst_ip)) {
            score++;
            temp_mask |= DST_IP_MASK_FLAG;
        }
        if ((policy->present_fields & SRC_PORT_MASK_FLAG) == SRC_PORT_MASK_FLAG &&
            ntohs(sport) == htons(policy->src_port)) {
            score++;
            temp_mask |= SRC_PORT_MASK_FLAG;
        }
        if ((policy->present_fields & DST_PORT_MASK_FLAG) == DST_PORT_MASK_FLAG &&
            ntohs(dport) >= htons(policy->dst_port_start) &&
            ntohs(dport) <= htons(policy->dst_port_end)) {
            score++;
            temp_mask |= DST_PORT_MASK_FLAG;
        }
        if ((policy->present_fields & PROTO_MASK_FLAG) == PROTO_MASK_FLAG &&
            protocol == policy->proto) {
            score++;
            temp_mask |= PROTO_MASK_FLAG;
        }

        if (score > best_score && temp_mask == policy->present_fields) {
            best_match = i;
            best_score = score;
        }
    }

    uint8_t new_tos = 0;  // Can 0 be used as default forwarding value?
    uint8_t new_dscp = 0;
    uint8_t new_priority = 0;
    uint8_t new_flow_lbl = 0;
    if (best_score > 0) {
        DscpPolicy* policy;
        if (ipv4) {
            policy = bpf_ipv4_dscp_policies_map_lookup_elem(&best_match);
        } else {
            policy = bpf_ipv6_dscp_policies_map_lookup_elem(&best_match);
        }

        if (policy) {
            new_dscp = policy->dscp_val;
            if (ipv4) {
                new_tos = UPDATE_TOS(new_dscp, tos);
            } else {
                new_priority = UPDATE_PRIORITY(new_dscp);
                new_flow_lbl = UPDATE_FLOW_LABEL(new_dscp, flow_lbl);
            }
        }
    } else
        return;

    RuleEntry value = {
        .src_ip = src_ip,
        .dst_ip = dst_ip,
        .ifindex = skb->ifindex,
        .src_port = sport,
        .dst_port = dport,
        .proto = protocol,
        .dscp_val = new_dscp,
    };

    // Update map with new policy.
    if (ipv4) {
        if (*selected_map == MAP_A) {
            bpf_ipv4_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
        } else {
            bpf_ipv4_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
        }
    } else {
        if (*selected_map == MAP_A) {
            bpf_ipv6_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
        } else {
            bpf_ipv6_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
        }
    }

    // Need to store bytes after updating map or program will not load.
    if (ipv4 && new_tos != (tos & 252)) {
        bpf_l3_csum_replace(skb, IP4_OFFSET(check, l2_header_size), htons(tos), htons(new_tos), 2);
        bpf_skb_store_bytes(skb, IP4_OFFSET(tos, l2_header_size), &new_tos, sizeof(new_tos), 0);
    } else if (!ipv4 && (new_priority != priority || new_flow_lbl != flow_lbl)) {
        bpf_skb_store_bytes(skb, l2_header_size, &new_priority, sizeof(new_priority), 0);
        bpf_skb_store_bytes(skb, l2_header_size + 1, &new_flow_lbl, sizeof(new_flow_lbl), 0);
    }
    return;
}

DEFINE_BPF_PROG_KVER("schedcls/set_dscp_ether", AID_ROOT, AID_SYSTEM,
                     schedcls_set_dscp_ether, KVER(5, 15, 0))
(struct __sk_buff* skb) {
    if (skb->pkt_type != PACKET_HOST) return TC_ACT_PIPE;

    if (skb->protocol == htons(ETH_P_IP)) {
        match_policy(skb, true, true);
    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        match_policy(skb, false, true);
    }

    // Always return TC_ACT_PIPE
    return TC_ACT_PIPE;
}

LICENSE("Apache 2.0");
CRITICAL("Connectivity");
