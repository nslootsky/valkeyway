package io.github.nslootsky.proxy.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.annotation.ParamLength;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Command("cluster")
@ParamLength(value = 1, option = 10)
public class ClusterCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(ClusterCommand.class);

    @Override
    public RedisToken execute(Request request) {
        if (request.getLength() < 1) {
            return RedisToken.error("ERR wrong number of arguments for 'cluster' command");
        }

        String subCmd = request.getParam(0).toString().toUpperCase();

        if ("INFO".equals(subCmd)) {
            return handleClusterInfo();
        } else if ("NODES".equals(subCmd)) {
            return handleClusterNodes();
        } else {
            return RedisToken.error("ERR unknown cluster subcommand '" + subCmd + "'");
        }
    }

    private RedisToken handleClusterInfo() {
        return RedisToken.error("This instance has cluster support disabled");
    }

    private RedisToken handleClusterNodes() {
        return RedisToken.error("This instance has cluster support disabled");
    }
}
