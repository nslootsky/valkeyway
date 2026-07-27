package io.github.nslootsky.proxy.handler;

import com.github.tonivade.resp.command.CommandSuite;
import com.github.tonivade.resp.command.RespCommand;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.commands.*;
import io.github.nslootsky.proxy.metrics.MetricsCollector;
import io.github.nslootsky.proxy.scan.ScanCursorStore;

/**
 * Command router for the proxy. Registers named handlers for supported commands
 * and falls back to CatchAllCommand for all others.
 */
public class ProxyCommandSuite extends CommandSuite {

    private final CatchAllCommand catchAllCommand;

    public ProxyCommandSuite(GlideClientCache glideClientCache, ScanCursorStore scanCursorStore, MetricsCollector metrics) {
        super();
        this.catchAllCommand = new CatchAllCommand(glideClientCache, metrics);

        addCommand("select", new SelectCommand(glideClientCache));
        addCommand("multi", new MultiCommand());
        addCommand("exec", new ExecCommand(glideClientCache));
        addCommand("discard", new DiscardCommand());
        addCommand("ping", new PingCommand(glideClientCache));
        addCommand("time", new TimeCommand(glideClientCache));
        addCommand("del", new DelCommand(glideClientCache));
        addCommand("unlink", new UnlinkCommand(glideClientCache));
        addCommand("mget", new MgetCommand(glideClientCache));
        addCommand("scan", new ScanCommand(glideClientCache, scanCursorStore));
        addCommand("proxy", new ProxyAdminCommand(glideClientCache));
        addCommand("hello", new HelloCommand(glideClientCache, metrics));
        addCommand("info", new InfoCommand(metrics));
        addCommand("cluster", new ClusterCommand());
    }

    @Override
    public RespCommand getCommand(String name) {
        RespCommand command = super.getCommand(name);
        if (command != null && command.getClass().getSimpleName().equals("NullCommand")) {
            return catchAllCommand;
        }
        return command;
    }
}
