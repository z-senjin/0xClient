package oxclient.framework;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What a game message means for the builder: one table from the chatbox's text to an event, so a task reacts
 * to what the game SAYS instead of waiting for a timeout. Many stalls on 2026-10-10 had the reason in the
 * chatbox ("You don't have enough inventory space", a level-up) while the task waited for nothing to happen.
 *
 * <p>The {@link Picker} reads new chatbox lines (it is their only reader), logs them as {@code [chat]}, turns
 * each into an event here, handles DEATH itself and hands the rest to the running task's
 * {@link Task#onEvent}. Adding a reaction is one row below plus the task that cares about it.</p>
 *
 * <p>Matching is on the line's text, ignoring case. Other players' public chat shows up in the same box, so
 * a player typing one of these sentences would trigger it -- rare, and the worst outcome is one wasted retry.</p>
 */
public enum ChatEvent {
    /** "Oh dear, you are dead!" */
    DEATH,
    /** "I can't reach that!" -- the target is behind something. */
    CANT_REACH,
    /** "There is no ammo left in your quiver." */
    NO_AMMO,
    /** "You need a Cooking level of 15 to ..." -- this method is not for this level. */
    LEVEL_TOO_LOW,
    /** "You can't light a fire here." */
    CANT_LIGHT,
    /** "Your inventory is too full to ..." / "You don't have enough inventory space ..." */
    INVENTORY_FULL,
    /** "Your account is currently restricted for trading. The restrictions will lift in 12 hours, 43 minutes, ..." */
    TRADE_RESTRICTED;

    private record Rule(Pattern pattern, ChatEvent event) {}

    private static Rule rule(String regex, ChatEvent e) { return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), e); }

    private static final List<Rule> RULES = List.of(
            rule("oh dear, you are dead", DEATH),
            rule("i can't reach that", CANT_REACH),
            rule("no ammo left", NO_AMMO),
            rule("you need an? [a-z]+ level of \\d+", LEVEL_TOO_LOW),
            rule("can't light a fire here", CANT_LIGHT),
            rule("inventory is too full|enough inventory space", INVENTORY_FULL),
            rule("restricted for trading", TRADE_RESTRICTED));

    private static final Pattern LIFT = Pattern.compile("lift in (?:(\\d+) hours?)?,? ?(?:(\\d+) minutes?)?", Pattern.CASE_INSENSITIVE);

    /** For TRADE_RESTRICTED: how long until trading is allowed, in ms, read from the message (12 h if it does not say). */
    public static long liftsInMs(String line) {
        java.util.regex.Matcher m = LIFT.matcher(line == null ? "" : line);
        if (!m.find() || m.group(1) == null && m.group(2) == null) return 12 * 3_600_000L;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1)), min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        return (h * 60 + min) * 60_000L;
    }

    /** The event this chatbox line means, or null. */
    public static ChatEvent of(String line) {
        if (line == null || line.isEmpty()) return null;
        for (Rule r : RULES) if (r.pattern().matcher(line).find()) return r.event();
        return null;
    }
}
