package ca.pkay.rcloneexplorer.util;

/** Recognizes the app's rclone config-writing command forms without mistaking option values. */
public final class ConfigMutationCommandPolicy {
    private ConfigMutationCommandPolicy() {
    }

    public static boolean isMutationCommand(String[] command) {
        if (command == null) return false;
        int index = 0;
        if (command.length > 0 && !command[0].startsWith("-")) {
            index = 1; // native executable path/name
        }
        for (; index < command.length; index++) {
            String token = command[index];
            if ("--config".equals(token) || "--cache-chunk-path".equals(token)
                    || "--cache-db-path".equals(token)) {
                index++;
                continue;
            }
            if (token.startsWith("--config=") || token.startsWith("--cache-chunk-path=")
                    || token.startsWith("--cache-db-path=")) {
                continue;
            }
            if (token.startsWith("-")) continue;
            if (!"config".equals(token)) return false;
            if (index + 1 >= command.length) return true; // interactive `rclone config`
            String action = command[index + 1];
            return !("dump".equals(action) || "providers".equals(action)
                    || "show".equals(action) || "file".equals(action)
                    || "paths".equals(action));
        }
        return false;
    }
}
