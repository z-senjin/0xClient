package oxclient.framework;

import oxclient.api.Walker;

/** Walk to a place. */
public final class WalkTask extends BaseTask {

    private final Place place;
    private Walker.Route route;

    public WalkTask(Place place) { this.place = place; }

    @Override public String name() { return "Walk to " + place.name(); }

    @Override public void start() { route = null; }

    @Override
    protected Result step(long now) {
        if (route == null) route = new Walker.Route(place.x(), place.y(), place.plane(), place.radius());
        Walker.Status st = route.step();
        state(st.name().toLowerCase().replace('_', ' '));
        return switch (st) {
            case ARRIVED -> Result.DONE;
            case NO_ROUTE -> fail("no route to " + place.name());
            case STUCK -> fail("stuck on the way to " + place.name());
            default -> Result.CONTINUE;
        };
    }
}
