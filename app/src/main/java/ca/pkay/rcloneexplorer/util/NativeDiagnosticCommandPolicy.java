package ca.pkay.rcloneexplorer.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Validates long-lived native command arguments and disables unsafe diagnostic sinks. */
public final class NativeDiagnosticCommandPolicy {

    public static final String DISABLED_NOTICE =
            "Native rclone daemon output is not persisted because it may contain credentials or private data.";

    static final int MAX_ARGUMENT_CHARS = 8192;
    private static final int MAX_ARGUMENT_COUNT = 256;
    private static final int MAX_COMMAND_UTF8_BYTES = 64 * 1024;
    private static final String INVALID_COMMAND = "Unsafe native command arguments";

    private NativeDiagnosticCommandPolicy() {
    }

    /**
     * Validates argv as discrete process arguments, then removes verbose output and diagnostic
     * options that can persist or expose private data. Authentication values required by the
     * native operation remain opaque argv values; this policy never logs or formats them.
     */
    public static String[] withoutNativeDiagnostics(String[] command) {
        validateCommand(command);

        ArrayList<String> safeCommand = new ArrayList<>(command.length);
        for (int i = 0; i < command.length; i++) {
            String argument = command[i];
            if ("--log-file".equals(argument) || "--dump".equals(argument)) {
                // Validation already checked the required value; remove the pair together.
                i++;
                continue;
            }
            if (argument.startsWith("--log-file=")
                    || argument.startsWith("--dump=")
                    || "--dump-headers".equals(argument)
                    || "--dump-bodies".equals(argument)
                    || isVerboseOption(argument)) {
                continue;
            }
            safeCommand.add(argument);
        }
        return safeCommand.toArray(new String[0]);
    }

    private static void validateCommand(String[] command) {
        if (command == null || command.length < 2 || command.length > MAX_ARGUMENT_COUNT) {
            throw invalidCommand();
        }

        int totalBytes = 0;
        for (String argument : command) {
            if (argument == null || argument.isEmpty() || argument.length() > MAX_ARGUMENT_CHARS) {
                throw invalidCommand();
            }
            // Reject NUL and other control characters before passing strings to native code.
            if (containsControlCharacter(argument)) throw invalidCommand();
            totalBytes += argument.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_COMMAND_UTF8_BYTES) throw invalidCommand();
        }

        if (!isSafeExecutable(command[0])) throw invalidCommand();

        boolean foundCommand = false;
        for (int i = 1; i < command.length; i++) {
            String argument = command[i];
            Option option = parseOption(argument);
            String optionName = option.name.toLowerCase(Locale.ROOT);

            if (isUnclassifiedSecretOption(optionName)) throw invalidCommand();

            if (isAuthenticationOption(optionName)) {
                String value;
                if (option.hasInlineValue) {
                    value = option.inlineValue;
                } else {
                    if (i + 1 >= command.length) throw invalidCommand();
                    value = command[++i];
                }
                if (value == null || value.isEmpty() || value.length() > MAX_ARGUMENT_CHARS
                        || containsControlCharacter(value)) {
                    throw invalidCommand();
                }
                // Auth values are intentionally opaque: punctuation is valid in passwords and
                // argv is passed directly to exec, never interpolated into a shell command.
                continue;
            }

            if (isValueOption(optionName)) {
                String value;
                if (option.hasInlineValue) {
                    value = option.inlineValue;
                } else {
                    if (i + 1 >= command.length) throw invalidCommand();
                    value = command[++i];
                }
                if (value == null || value.isEmpty() || value.startsWith("--")) {
                    throw invalidCommand();
                }
                validateOrdinaryArgument(value);
                if (isPathTraversal(value)) throw invalidCommand();
                if (isPathOption(optionName) && !isSafePath(value)) throw invalidCommand();
                if (isDiagnosticOption(optionName) && containsSecretMaterial(value)) {
                    throw invalidCommand();
                }
                continue;
            }

            validateOrdinaryArgument(argument);
            if (isPathTraversal(argument)) throw invalidCommand();
            if ("rcd".equals(argument)) {
                foundCommand = true;
            } else if ("serve".equals(argument)) {
                if (i + 1 >= command.length || !isServeProtocol(command[i + 1])) {
                    throw invalidCommand();
                }
                foundCommand = true;
            }
        }

        if (!foundCommand) throw invalidCommand();
    }

    private static Option parseOption(String argument) {
        if (!argument.startsWith("--")) return new Option(argument, false, null);
        int equals = argument.indexOf('=');
        if (equals < 0) return new Option(argument, false, null);
        return new Option(argument.substring(0, equals), true, argument.substring(equals + 1));
    }

    private static boolean isAuthenticationOption(String option) {
        return "--rc-user".equals(option) || "--rc-pass".equals(option)
                || "--user".equals(option) || "--pass".equals(option);
    }

    private static boolean isUnclassifiedSecretOption(String option) {
        return "--password".equals(option) || "--token".equals(option)
                || "--api-key".equals(option) || "--apikey".equals(option)
                || "--client-secret".equals(option) || "--access-key".equals(option)
                || "--secret-key".equals(option) || "--authorization".equals(option);
    }

    private static boolean isValueOption(String option) {
        switch (option) {
            case "--config":
            case "--cache-chunk-path":
            case "--cache-db-path":
            case "--log-file":
            case "--dump":
            case "--log-level":
            case "--log-format":
            case "--stats":
            case "--stats-log-level":
            case "--stats-file-name-length":
            case "--rc-addr":
            case "--addr":
            case "--baseurl":
            case "--transfers":
            case "--buffer-size":
            case "--multi-thread-streams":
            case "--vfs-read-chunk-size":
            case "--vfs-read-chunk-size-limit":
            case "--dir-cache-time":
                return true;
            default:
                return false;
        }
    }

    private static boolean isPathOption(String option) {
        return "--config".equals(option) || "--cache-chunk-path".equals(option)
                || "--cache-db-path".equals(option) || "--log-file".equals(option);
    }

    private static boolean isDiagnosticOption(String option) {
        return "--log-file".equals(option) || "--dump".equals(option)
                || "--log-level".equals(option) || "--log-format".equals(option)
                || "--stats".equals(option) || "--stats-log-level".equals(option)
                || "--stats-file-name-length".equals(option);
    }

    private static boolean isSafeExecutable(String executable) {
        String name = executable;
        if (executable.startsWith("/")) {
            if (!isSafePath(executable)) return false;
            name = executable.substring(executable.lastIndexOf('/') + 1);
        } else if (executable.startsWith("-") || executable.indexOf('/') >= 0
                || executable.indexOf('\\') >= 0) {
            return false;
        }
        // The only executable owned by these callers is the packaged rclone binary.
        return "librclone.so".equals(name);
    }

    private static boolean isSafePath(String path) {
        if (path.isEmpty() || path.startsWith("-") || path.startsWith("//")
                || path.startsWith("~")) return false;
        validateOrdinaryArgument(path);
        if (isPathTraversal(path)) return false;
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty() && i != 0) return false;
            if (".".equals(segments[i]) || "..".equals(segments[i])) return false;
        }
        return true;
    }

    private static boolean isPathTraversal(String value) {
        String[] segments = value.split("[/\\\\]", -1);
        for (String segment : segments) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    private static void validateOrdinaryArgument(String argument) {
        if (argument.length() > MAX_ARGUMENT_CHARS || containsControlCharacter(argument)) {
            throw invalidCommand();
        }
        for (int i = 0; i < argument.length(); i++) {
            if (isShellSyntax(argument.charAt(i))) throw invalidCommand();
        }
        if (containsSecretMaterial(argument)) throw invalidCommand();
    }

    private static boolean isShellSyntax(char c) {
        switch (c) {
            case ';': case '|': case '&': case '$': case '`': case '<': case '>':
            case '(': case ')': case '{': case '}': case '[': case ']': case '*':
            case '?': case '~': case '\'': case '"': case '\\': case '#':
                return true;
            default:
                return false;
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean containsSecretMaterial(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        String[] markers = {"password=", "password:", "passwd=", "passwd:",
                "token=", "token:", "secret=", "secret:", "credential=", "credential:",
                "authorization=", "authorization:", "api_key=", "api-key=", "apikey=",
                "access_key=", "access-key=", "private_key=", "private-key=",
                "bearer ", "basic "};
        for (String marker : markers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static boolean isServeProtocol(String value) {
        return "http".equals(value) || "ftp".equals(value)
                || "dlna".equals(value) || "webdav".equals(value);
    }

    private static boolean isVerboseOption(String argument) {
        if ("--verbose".equals(argument) || argument.startsWith("--verbose=")) return true;
        if (argument.length() < 2 || argument.charAt(0) != '-') return false;
        for (int i = 1; i < argument.length(); i++) {
            if (argument.charAt(i) != 'v') return false;
        }
        return true;
    }

    private static IllegalArgumentException invalidCommand() {
        // Never echo argv values here: callers may include authentication material.
        return new IllegalArgumentException(INVALID_COMMAND);
    }

    private static final class Option {
        final String name;
        final boolean hasInlineValue;
        final String inlineValue;

        Option(String name, boolean hasInlineValue, String inlineValue) {
            this.name = name;
            this.hasInlineValue = hasInlineValue;
            this.inlineValue = inlineValue;
        }
    }
}
