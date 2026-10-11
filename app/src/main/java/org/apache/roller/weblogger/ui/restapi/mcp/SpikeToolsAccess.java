package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.List;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;

/** Task 1 only: lets boot/McpServerConfig reach SpikeTools. Deleted in Task 4. */
public final class SpikeToolsAccess {
    private SpikeToolsAccess() {
    }

    public static List<SyncToolSpecification> all(ApiDispatcher dispatcher) {
        return SpikeTools.all(dispatcher);
    }
}
