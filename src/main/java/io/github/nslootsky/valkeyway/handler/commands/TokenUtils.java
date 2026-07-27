package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.models.ClusterValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utilities for converting Glide results to RedisToken and cleaning error messages.
 */
public class TokenUtils {

    private TokenUtils() {}

    public static String cleanErrorMessage(String msg) {
        if (msg == null) return "Unknown error";
        String[] prefixes = {
                "glide.api.models.exceptions.RequestException: ",
                "glide.api.models.exceptions.RequestException: Received crossed slots in pipeline- ",
                "ResponseError: "
        };
        for (String prefix : prefixes) {
            if (msg.startsWith(prefix)) {
                msg = msg.substring(prefix.length());
            }
        }
        return msg;
    }

    public static String summarize(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> s.length() > 50 ? s.substring(0, 50) + "..." : s;
            case Number n -> n.toString();
            case Object[] arr -> "[" + arr.length + "]";
            default -> value.toString();
        };
    }

    public static RedisToken toRedisToken(ClusterValue<Object> clusterValue) {
        if (clusterValue == null) {
            return RedisToken.nullString();
        }
        if (clusterValue.hasSingleData()) {
            Object val = clusterValue.getSingleValue();
            return toRedisToken(val);
        } else if (clusterValue.hasMultiData()) {
            Map<String, Object> multiValue = clusterValue.getMultiValue();
            return toRedisToken(multiValue);
        }
        return RedisToken.nullString();
    }

    public static RedisToken toRedisToken(Object value) {
        return switch (value) {
            case null -> RedisToken.nullString();
            case String s -> RedisToken.string(s);
            case Number n -> RedisToken.integer(n.intValue());
            case Boolean b -> RedisToken.integer(b ? 1 : 0);
            case Object[] arr -> toRedisArray(arr);
            case Map<?, ?> map -> {
                List<RedisToken> tokens = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    tokens.add(toRedisToken(entry.getKey()));
                    tokens.add(toRedisToken(entry.getValue()));
                }
                yield RedisToken.array(tokens);
            }
            default -> RedisToken.string(value.toString());
        };
    }

    public static RedisToken toRedisArray(Object[] values) {
        if (values == null) {
            return RedisToken.nullString();
        }
        List<RedisToken> tokens = new ArrayList<>();
        for (Object v : values) {
            tokens.add(toRedisToken(v));
        }
        return RedisToken.array(tokens);
    }
}
