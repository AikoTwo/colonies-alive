package com.aiko.coloniesalive.client;

import com.minecolonies.core.client.render.worldevent.PathfindingDebugRenderer;

/**
 * Wipes what MineColonies' path debug mode draws (boxes with cost/heuristic labels).
 */
public final class ClientPathDebug
{
    private ClientPathDebug() {}

    public static void clear()
    {
        PathfindingDebugRenderer.lastDebugNodesVisited.clear();
        PathfindingDebugRenderer.lastDebugNodesVisitedLater.clear();
        PathfindingDebugRenderer.lastDebugNodesNotVisited.clear();
        PathfindingDebugRenderer.lastDebugNodesPath.clear();
        PathfindingDebugRenderer.debugNodesOrgPath.clear();
        PathfindingDebugRenderer.debugNodesExtra.clear();
    }
}
