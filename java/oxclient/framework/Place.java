package oxclient.framework;

/** A named spot in the world: a tile, its floor, and how close counts as "there". */
public record Place(String name, int x, int y, int plane, int radius) {

    /** Tile distance from (x, y) on the same floor; MAX_VALUE on another floor. */
    public int distance(int wx, int wy, int wplane) {
        return wplane != plane ? Integer.MAX_VALUE : Math.max(Math.abs(wx - x), Math.abs(wy - y));
    }
}
