package oxclient.api;

/**
 * Anything with a right-click menu: an NPC, a player, a piece of scenery, a ground item, an item in your
 * inventory. Every one of them answers the same questions and takes options the same way, so a script can
 * say {@code thing.interact("Chop down")} without caring which kind of thing it is.
 *
 * <p><b>Options are numbered by their position in the menu, 1..5</b> -- {@code options()[n - 1]} is option
 * {@code n}. The game numbers some menus differently underneath (the inventory's are 2, 3, 4, 6, 7); each
 * implementation translates, so 1..5 is the only numbering a script ever sees.</p>
 *
 * <p>An option name is matched ignoring case. A name the thing does not have is refused (false) rather than
 * guessed at, so a typo never clicks something else.</p>
 */
public interface Interactable {

    /** The game's id for what this is: an NPC type, a loc id, an item id. For a player, see {@link Entity#id}. */
    int id();

    /** Its name ("Goblin", "Oak tree", "Logs"), or "" when the client has not cached it right now. */
    String name();

    /** Its five menu options; option n is {@code [n - 1]}, "" where there is none. */
    String[] options();

    /**
     * Take option {@code option} (1..5, its position in {@link #options}).
     *
     * @return true when the action was handed to the game -- not that it has happened yet; confirm the
     *     effect (an animation, an inventory change) with {@link Await}
     */
    boolean interact(int option);

    /** Take the option named {@code option} ("Attack", "Chop down", "Eat"), ignoring case. False when it has none. */
    default boolean interact(String option) {
        int n = option(option);
        return n > 0 && interact(n);
    }

    /** The position (1..5) of the option named {@code option}, ignoring case, or -1. */
    default int option(String option) {
        if (option == null) return -1;
        String[] ops = options();
        for (int i = 0; i < ops.length; i++) if (ops[i] != null && ops[i].equalsIgnoreCase(option)) return i + 1;
        return -1;
    }

    /** True when it has an option named {@code option}, ignoring case. */
    default boolean hasOption(String option) { return option(option) > 0; }

    /** True when its name is exactly {@code name}, ignoring case. */
    default boolean isNamed(String name) { return name != null && name.equalsIgnoreCase(name()); }
}
