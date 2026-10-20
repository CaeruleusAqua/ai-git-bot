package org.remus.giteabot.util;

/** Character-level helpers for text that is cut down to a budget. */
public final class TextSupport {

    private TextSupport() {
    }

    /**
     * Cuts to at most {@code maxChars} characters without leaving half of a surrogate pair behind.
     *
     * <p>{@code substring} counts Java chars, so it can end between a high and a low surrogate; the
     * lone half then shows up as a replacement character wherever the text lands. A cut that would
     * end on a high surrogate stops one char earlier, so the result stays within the cap and remains
     * well-formed. A budget of zero or less yields the empty string.</p>
     */
    public static String cutAtCodePoint(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return value.substring(0, end);
    }
}
