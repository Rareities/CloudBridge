package io.github.rareities.protonbridge.util;

import io.github.rareities.protonbridge.Items.FilterEntry;
import io.github.rareities.protonbridge.Items.Task;
import java.util.ArrayList;
import java.util.List;

/** Builds one predictable filter rule family for saved filters and task flags. */
public final class FilterRules {
    private FilterRules() {}

    private static String unquote(String value) {
        if (!value.isEmpty() && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
            if (value.length() < 2 || value.charAt(value.length() - 1) != value.charAt(0))
                throw new IllegalArgumentException("Finish the quoted filter value");
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static String pattern(String value) {
        String pattern = unquote(value == null ? "" : value.trim()).trim();
        if (pattern.isEmpty()) throw new IllegalArgumentException("Enter a filter pattern, for example /.nomedia");
        if (pattern.startsWith("--")) throw new IllegalArgumentException("Enter a pattern here; put --exclude or --include in the task flag editor");
        for (int i = 0; i < pattern.length(); i++)
            if (Character.isISOControl(pattern.charAt(i))) throw new IllegalArgumentException("Filter patterns cannot contain control characters");
        return pattern;
    }

    public static String savedPattern(FilterEntry entry) {
        if (entry.filterType != FilterEntry.FILTER_INCLUDE && entry.filterType != FilterEntry.FILTER_EXCLUDE)
            throw new IllegalArgumentException("Choose Include or Exclude for each filter");
        String value = entry.filter == null ? "" : entry.filter.trim();
        String prefix = entry.filterType == FilterEntry.FILTER_INCLUDE ? "+ " : "- ";
        if (value.startsWith(prefix)) value = value.substring(2);
        else if (value.startsWith("+ ") || value.startsWith("- "))
            throw new IllegalArgumentException("Choose the matching Include or Exclude type and enter only the pattern");
        return pattern(value);
    }

    public static List<String> arguments(List<FilterEntry> saved, List<String> advanced) {
        List<String> excludes = new ArrayList<>(), includes = new ArrayList<>();
        List<String> explicit = new ArrayList<>(), other = new ArrayList<>();
        boolean reset = false;
        for (FilterEntry entry : saved) {
            String value = savedPattern(entry);
            (entry.filterType == FilterEntry.FILTER_EXCLUDE ? excludes : includes)
                    .add("--filter=" + (entry.filterType == FilterEntry.FILTER_EXCLUDE ? "- " : "+ ") + value);
        }
        for (String flag : advanced) {
            if (flag.startsWith("--exclude=")) excludes.add("--filter=- " + pattern(flag.substring(10)));
            else if (flag.startsWith("--include=")) includes.add("--filter=+ " + pattern(flag.substring(10)));
            else if (flag.startsWith("--filter=")) {
                String rule = unquote(flag.substring(9));
                if (!rule.equals("!") && !rule.startsWith("+ ") && !rule.startsWith("- "))
                    throw new IllegalArgumentException("Use --filter=- pattern, --filter=+ pattern, or --include/--exclude");
                if (rule.equals("!")) reset = true;
                explicit.add("--filter=" + rule);
            } else other.add(flag);
        }
        if (reset && (!saved.isEmpty() || !excludes.isEmpty() || !includes.isEmpty()))
            throw new IllegalArgumentException("A filter reset cannot be combined with a saved filter or --include/--exclude");
        List<String> result = new ArrayList<>(excludes);
        result.addAll(explicit); // Expert rules keep their relative order and literal pattern whitespace.
        result.addAll(includes);
        if (!includes.isEmpty()) result.add("--filter=- /**");
        result.addAll(other);
        return result;
    }

    /** Older destructive tasks must be reviewed before restrictive include semantics apply. */
    public static void requireReviewedDeletion(Task task, List<FilterEntry> filters) {
        if (task.getDeleteExcluded() && task.getFilterPolicyVersion() < 1)
            for (FilterEntry entry : filters) if (entry.filterType == FilterEntry.FILTER_INCLUDE)
                throw new IllegalArgumentException("Include filtering has changed. Review and save this task's Delete excluded files setting before running it.");
    }
}
