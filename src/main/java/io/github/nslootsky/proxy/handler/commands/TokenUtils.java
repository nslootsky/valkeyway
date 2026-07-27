package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.models.ClusterValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        if (clusterValue.hasSingleData()) {
            return toRedisToken(clusterValue.getSingleValue());
        } else if (clusterValue.hasMultiData()) {
            Map<String, Object> multiValue = clusterValue.getMultiValue();
            StringBuilder sb = new StringBuilder();
            for (Object val : multiValue.values()) {
                sb.append(val);
            }
            return RedisToken.string(sb.toString());
        }
        return RedisToken.nullString();
    }

    public static RedisToken toRedisToken(Object value) {
        return switch (value) {
            case null -> RedisToken.nullString();
            case String s -> RedisToken.string(s);
            case Number n -> RedisToken.string(n.toString());
            case Boolean b -> RedisToken.integer(b ? 1 : 0);
            case Object[] arr -> toRedisArray(arr);
            case Map<?, ?> map -> {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
                yield RedisToken.string(sb.toString());
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
