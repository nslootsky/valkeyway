package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.command.Session;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.SessionState;
import io.github.nslootsky.proxy.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CatchAllCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(CatchAllCommand.class);

    private final GlideClientCache glideClientCache;
    private final MetricsCollector metrics;

    public CatchAllCommand(GlideClientCache glideClientCache, MetricsCollector metrics) {
        this.glideClientCache = glideClientCache;
        this.metrics = metrics;
    }

    @Override
    public RedisToken execute(Request request) {
        Session session = request.getSession();
        String command = request.getCommand();
        List<String> args = toArgs(request);
        var timer = metrics.startTimer();

        log.debug("CMD {} args={}", command, args);

        try {
            metrics.recordCommand(command);
            if (SessionState.isInTransaction(session)) {
                queueTransactionCommand(session, args);
                return RedisToken.status("QUEUED");
            }

            RedisToken result = handleCustomCommand(session, args);
            if (result.getType().name().equals("ERROR")) {
                metrics.recordError();
            }
            return result;
        } catch (Exception e) {
            metrics.recordError();
            log.error("Error handling command {}", command, e);
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        } finally {
            metrics.stopTimer(timer);
        }
    }

    private List<String> toArgs(Request request) {
        List<String> args = new ArrayList<>();
        args.add(request.getCommand());
        for (var param : request.getParams()) {
            args.add(param.toString());
        }
        return args;
    }

    private void queueTransactionCommand(Session session, List<String> args) {
        String txError = SessionState.getTransactionError(session);
        if (txError != null) {
            return;
        }
        List<String> keys = extractKeys(args);
        Set<Integer> slots = SessionState.getTransactionSlots(session);
        for (String key : keys) {
            slots.add(key.hashCode() & 0x3FFF);
        }
        SessionState.setTransactionSlots(session, slots);
        List<String[]> commands = SessionState.getTransactionCommands(session);
        commands.add(args.toArray(new String[0]));
        SessionState.setTransactionCommands(session, commands);
    }

    private List<String> extractKeys(List<String> args) {
        String command = args.getFirst().toUpperCase();
        if (args.size() < 2) {
            return List.of();
        }
        return switch (command) {
            case "DEL", "UNLINK", "MGET", "EXISTS", "TOUCH" -> args.subList(1, args.size());
            case "SET", "GET", "HSET", "HGET", "INCR", "DECR" -> List.of(args.get(1));
            default -> List.of(args.get(1));
        };
    }

    private RedisToken handleCustomCommand(Session session, List<String> args) {
        String[] cmdArgs = args.toArray(new String[0]);
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(session, glideClientCache);
            ClusterValue<Object> result = client.customCommand(cmdArgs).get();
            log.debug("OK {} result={}", cmdArgs[0], TokenUtils.summarize(result));
            return TokenUtils.toRedisToken(result);
        } catch (Exception e) {
            log.error("ERR {} error={}", cmdArgs[0], e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
