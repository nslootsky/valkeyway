package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import io.github.nslootsky.proxy.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INFO command handler. Returns proxy-specific info with role:master
 * to be compatible with Glide's STANDARD mode detection.
 */
public class InfoCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(InfoCommand.class);

    private final MetricsCollector metrics;

    public InfoCommand(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public RedisToken execute(Request request) {
        String section = request.getLength() > 0 ? request.getParam(0).toString().toUpperCase() : "ALL";
        log.debug("INFO section={}", section);
        try {
            return handleInfo();
        } catch (Exception e) {
            log.error("INFO ERR {}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }

    private RedisToken handleInfo() {
        String sb = "# Replication\r\n" +
                "role:master\r\n" +
                "connected_slaves:0\r\n" +
                "# Stats\r\n" +
                "total_commands_processed:" + metrics.getTotalCommands() + "\r\n" +
                "total_errors:" + metrics.getTotalErrors() + "\r\n";
        return RedisToken.string(sb);
    }


}
