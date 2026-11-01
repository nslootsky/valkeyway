/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import glide.api.models.configuration.RequestRoutingConfiguration.SlotIdRoute;
import glide.api.models.configuration.RequestRoutingConfiguration.SlotType;

/**
 * One reusable Glide route per hash slot, targeting the slot's primary.
 *
 * <p>Passing an explicit single-node route to {@code customCommand} matters beyond picking the node:
 * without one, Glide's {@code ClusterValue.of()} treats any map-shaped reply (HGETALL, ZRANGE
 * WITHSCORES, XRANGE, ...) as a multi-node result. It then rebuilds it as an unordered HashMap
 * and decodes every key as UTF-8 text, which scrambles order and corrupts binary keys
 * (valkey-glide issue #4963). With a single-node route Glide returns the reply untouched.
 *
 * <p>Relies on upstream behavior: [upstream:G5] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class SlotRoutes {

    private static final SlotIdRoute[] PRIMARY = new SlotIdRoute[ClusterSlots.SLOT_COUNT];

    static {
        for (int slot = 0; slot < PRIMARY.length; slot++) {
            PRIMARY[slot] = new SlotIdRoute(slot, SlotType.PRIMARY);
        }
    }

    private SlotRoutes() {}

    public static SlotIdRoute primary(int slot) {
        return PRIMARY[slot];
    }
}
