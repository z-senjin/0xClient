package oxclient.api;

import java.util.List;
import java.util.function.Supplier;

import oxclient.api.Scenery.SceneObject;

/**
 * Finding scenery: {@link Scenery#query()}. Everything a {@link WorldQuery} does, plus which layer.
 *
 * <pre>{@code
 * SceneObject booth = Scenery.query().withOption("Bank").nearest();
 * SceneObject door  = Scenery.query().walls().named("Door").withOption("Open").nearest();
 * }</pre>
 */
public final class SceneryQuery extends WorldQuery<SceneObject, SceneryQuery> {

    SceneryQuery(Supplier<List<SceneObject>> source) { super(source); }

    /** Keep only walls, fences, doors and gates. */
    public SceneryQuery walls() { return where(SceneObject::isWall); }

    /** Keep only game objects: trees, rocks, booths -- everything that is not a wall. */
    public SceneryQuery objects() { return where(o -> !o.isWall()); }
}
