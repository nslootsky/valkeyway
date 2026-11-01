/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import glide.api.models.GlideString;

/** Command arguments as Glide takes them: binary-safe {@link GlideString}s. */
public final class GlideArgs {

    private GlideArgs() {}

    public static GlideString[] of(byte[][] args) {
        GlideString[] glide = new GlideString[args.length];
        for (int i = 0; i < args.length; i++) {
            glide[i] = GlideString.of(args[i]);
        }
        return glide;
    }

    /** {@code prefix} followed by {@code args}, e.g. {@code WATCH key...} or {@code COMMAND GETKEYS cmd...}. */
    public static GlideString[] of(String[] prefix, byte[][] args) {
        GlideString[] glide = new GlideString[prefix.length + args.length];
        for (int i = 0; i < prefix.length; i++) {
            glide[i] = GlideString.of(prefix[i]);
        }
        for (int i = 0; i < args.length; i++) {
            glide[prefix.length + i] = GlideString.of(args[i]);
        }
        return glide;
    }
}
