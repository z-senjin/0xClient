package oxclient.api;

import java.util.List;

/**
 * Finding other players.
 *
 * <p>Each one is an {@link Entity}: where they stand, what they are animating, their name (PLAYER_NAME_PTR,
 * verified on client-241-3) and their combat level ({@link Entity#id}, derived but not yet verified live).
 * Their right-click options are not wired, so {@code interact} on a player returns false.</p>
 */
public final class Players {

    private Players() {}

    /** Every visible player except you. */
    public static List<Entity> all() { return Game.players(); }

    /** Start a search over the other visible players. See {@link EntityQuery}. */
    public static EntityQuery query() { return new EntityQuery(Game::players); }

    /** The closest other player, or null when you are alone. */
    public static Entity nearest() {
        Entity best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Entity e : all()) {
            int d = e.distance();
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    /** How many other players are within {@code radius} tiles of you. */
    public static int countWithin(int radius) {
        int n = 0;
        for (Entity e : all()) if (e.distance() <= radius) n++;
        return n;
    }
}
