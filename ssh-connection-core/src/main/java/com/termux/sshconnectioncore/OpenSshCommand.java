package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A non-evaluating parser for the simple command form accepted by the SSH profile UI.
 *
 * <p>The parser deliberately rejects shell control operators, expansions and globs. This lets
 * callers insert OpenSSH options before the destination and append exactly one remote command
 * without evaluating profile text as a second shell program.</p>
 */
public final class OpenSshCommand {

    private static final String OPTIONS_WITH_VALUE = "BbcDEeFIiJLlmOoPpQRSWw";

    @NonNull private final List<String> commandTokens;
    @NonNull private final List<String> baseTokens;
    private final int sshIndex;
    private final int destinationIndex;
    private final int optionInsertionIndex;
    private final boolean usesSshpass;

    private OpenSshCommand(@NonNull List<String> commandTokens, int sshIndex,
                           int destinationIndex,
                           int optionInsertionIndex, boolean usesSshpass) {
        this.commandTokens = Collections.unmodifiableList(new ArrayList<>(commandTokens));
        this.baseTokens = Collections.unmodifiableList(new ArrayList<>(
            commandTokens.subList(0, destinationIndex + 1)));
        this.sshIndex = sshIndex;
        this.destinationIndex = destinationIndex;
        this.optionInsertionIndex = optionInsertionIndex;
        this.usesSshpass = usesSshpass;
    }

    @NonNull
    public static OpenSshCommand parse(@NonNull String rawCommand) {
        List<String> tokens = splitLiteralShellWords(rawCommand);
        if (tokens.isEmpty()) throw new IllegalArgumentException("SSH command is empty");

        int cursor = 0;
        if ("env".equals(executableName(tokens.get(cursor)))) {
            cursor++;
            while (cursor < tokens.size()) {
                String token = tokens.get(cursor);
                if ("--".equals(token)) {
                    cursor++;
                    break;
                }
                if ("-i".equals(token) || "--ignore-environment".equals(token)) {
                    cursor++;
                    continue;
                }
                if ("-u".equals(token) || "--unset".equals(token)) {
                    if (cursor + 1 >= tokens.size()) {
                        throw new IllegalArgumentException("Missing value for env option " + token);
                    }
                    cursor += 2;
                    continue;
                }
                if ((token.startsWith("-u") && token.length() > 2) ||
                    token.startsWith("--unset=")) {
                    cursor++;
                    continue;
                }
                if (token.startsWith("-")) {
                    throw new IllegalArgumentException("Unsupported env wrapper option " + token);
                }
                if (isEnvironmentAssignment(token)) {
                    cursor++;
                    continue;
                }
                break;
            }
        }

        boolean sshpass = false;
        if (cursor < tokens.size() && "sshpass".equals(executableName(tokens.get(cursor)))) {
            sshpass = true;
            cursor++;
            while (cursor < tokens.size()) {
                String token = tokens.get(cursor);
                if ("--".equals(token)) {
                    cursor++;
                    break;
                }
                if (!token.startsWith("-") || "-".equals(token)) break;
                boolean consumesNext = sshpassOptionConsumesFollowingToken(token);
                cursor++;
                if (consumesNext) {
                    if (cursor >= tokens.size()) {
                        throw new IllegalArgumentException(
                            "Missing value for sshpass option " + token);
                    }
                    cursor++;
                }
            }
        }
        if (cursor >= tokens.size() || !"ssh".equals(executableName(tokens.get(cursor)))) {
            throw new IllegalArgumentException("Only env and sshpass may wrap the SSH executable");
        }
        int sshIndex = cursor;

        int destinationIndex = -1;
        int insertionIndex = -1;
        boolean endOfOptions = false;
        for (int i = sshIndex + 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (endOfOptions) {
                destinationIndex = i;
                break;
            }
            if ("--".equals(token)) {
                endOfOptions = true;
                insertionIndex = i;
                continue;
            }
            if (token.startsWith("-") && token.length() > 1) {
                if (optionConsumesFollowingToken(token)) {
                    if (i + 1 >= tokens.size()) {
                        throw new IllegalArgumentException("Missing value for SSH option " + token);
                    }
                    i++;
                }
                continue;
            }
            destinationIndex = i;
            break;
        }

        if (destinationIndex < 0 || tokens.get(destinationIndex).isEmpty()) {
            throw new IllegalArgumentException("SSH destination not found");
        }
        if (insertionIndex < 0) insertionIndex = destinationIndex;

        return new OpenSshCommand(tokens, sshIndex, destinationIndex, insertionIndex, sshpass);
    }

    @Nullable
    public static OpenSshCommand tryParse(@Nullable String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) return null;
        try {
            return parse(rawCommand);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean usesSshpass() {
        return usesSshpass;
    }

    /** Return the destination token, without any remote command that followed it. */
    @NonNull
    String destination() {
        return baseTokens.get(destinationIndex);
    }

    /**
     * Return the first value supplied through an OpenSSH {@code -o} argument.
     * OpenSSH treats the option keyword and value as one argument, either as
     * {@code Name=value} or as the quoted {@code Name value} form.
     */
    @Nullable
    String firstOpenSshOptionValue(@NonNull String optionName) {
        String expected = optionName.trim();
        for (int i = sshIndex + 1; i < optionInsertionIndex; i++) {
            String token = commandTokens.get(i);
            if (!token.startsWith("-") || token.length() < 2) continue;
            for (int position = 1; position < token.length(); position++) {
                char option = token.charAt(position);
                if (OPTIONS_WITH_VALUE.indexOf(option) < 0) continue;
                String value;
                if (position + 1 < token.length()) {
                    value = token.substring(position + 1);
                } else if (i + 1 < optionInsertionIndex) {
                    value = commandTokens.get(++i);
                } else {
                    value = "";
                }
                if (option == 'o' && openSshOptionName(value).equalsIgnoreCase(expected)) {
                    return openSshOptionValue(value);
                }
                break;
            }
        }
        return null;
    }

    /** Return the first value for a short option such as {@code -p}, {@code -l}, or {@code -i}. */
    @Nullable
    String firstShortOptionValue(char expectedOption) {
        for (int i = sshIndex + 1; i < optionInsertionIndex; i++) {
            String token = commandTokens.get(i);
            if (!token.startsWith("-") || token.length() < 2) continue;
            for (int position = 1; position < token.length(); position++) {
                char option = token.charAt(position);
                if (OPTIONS_WITH_VALUE.indexOf(option) < 0) continue;
                String value;
                if (position + 1 < token.length()) {
                    value = token.substring(position + 1);
                } else if (i + 1 < optionInsertionIndex) {
                    value = commandTokens.get(++i);
                } else {
                    value = "";
                }
                if (option == expectedOption) return value;
                break;
            }
        }
        return null;
    }

    /** Return the first effective occurrence of a long option or its short-form equivalent. */
    @Nullable
    String firstOptionValue(@NonNull String optionName, char shortOption) {
        String expected = optionName.trim();
        for (int i = sshIndex + 1; i < optionInsertionIndex; i++) {
            String token = commandTokens.get(i);
            if (!token.startsWith("-") || token.length() < 2) continue;
            for (int position = 1; position < token.length(); position++) {
                char option = token.charAt(position);
                if (OPTIONS_WITH_VALUE.indexOf(option) < 0) continue;
                String value;
                if (position + 1 < token.length()) {
                    value = token.substring(position + 1);
                } else if (i + 1 < optionInsertionIndex) {
                    value = commandTokens.get(++i);
                } else {
                    value = "";
                }
                if (option == shortOption) return value;
                if (option == 'o' && openSshOptionName(value).equalsIgnoreCase(expected)) {
                    return openSshOptionValue(value);
                }
                break;
            }
        }
        return null;
    }

    public boolean hasOption(@NonNull String optionName) {
        String expected = optionName.trim();
        for (int i = sshIndex + 1; i < optionInsertionIndex; i++) {
            String token = baseTokens.get(i);
            if ("--".equals(token)) break;
            if (!token.startsWith("-") || token.length() < 2) continue;
            for (int position = 1; position < token.length(); position++) {
                char option = token.charAt(position);
                boolean takesValue = OPTIONS_WITH_VALUE.indexOf(option) >= 0;
                String value = null;
                if (takesValue) {
                    if (position + 1 < token.length()) {
                        value = token.substring(position + 1);
                    } else if (i + 1 < optionInsertionIndex) {
                        value = baseTokens.get(++i);
                    }
                }
                if (option == 'o' && value != null &&
                    openSshOptionName(value).equalsIgnoreCase(expected)) {
                    return true;
                }
                if (option == 'S' && "ControlPath".equalsIgnoreCase(expected)) return true;
                if (option == 'i' && "IdentityFile".equalsIgnoreCase(expected)) return true;
                if (takesValue) break;
            }
        }
        return false;
    }

    public boolean hasForcedTty() {
        int ttyFlags = 0;
        for (int i = sshIndex + 1; i < optionInsertionIndex; i++) {
            String token = baseTokens.get(i);
            if ("--".equals(token)) break;
            if (!token.startsWith("-") || token.length() < 2) continue;
            for (int position = 1; position < token.length(); position++) {
                char option = token.charAt(position);
                if (option == 't') ttyFlags++;
                else if (option == 'T') ttyFlags = 0;
                if (OPTIONS_WITH_VALUE.indexOf(option) >= 0) {
                    if (position + 1 == token.length() && i + 1 < optionInsertionIndex) i++;
                    break;
                }
            }
        }
        return ttyFlags >= 2;
    }

    @NonNull
    public String renderBaseCommand() {
        return renderTokens(baseTokens);
    }

    /** Add already-tokenized SSH arguments immediately before the destination. */
    @NonNull
    public String renderRemoteCommand(@NonNull List<String> additionalSshArguments,
                                      boolean forceTty, @NonNull String remoteCommand) {
        ArrayList<String> tokens = new ArrayList<>(baseTokens.size() + additionalSshArguments.size() + 2);
        tokens.addAll(baseTokens.subList(0, optionInsertionIndex));
        tokens.addAll(additionalSshArguments);
        if (forceTty && !hasForcedTty()) tokens.add("-tt");
        tokens.addAll(baseTokens.subList(optionInsertionIndex, baseTokens.size()));
        tokens.add(remoteCommand);
        return renderTokens(tokens);
    }

    /**
     * Remove selected {@code -o} options and insert replacement arguments before the destination.
     * The complete original command, including a remote command, is retained. This is used for
     * security-sensitive options whose first command-line occurrence must not be left in place.
     */
    @NonNull
    String renderReplacingOpenSshOptions(@NonNull Set<String> optionNames,
                                         @NonNull List<String> replacementArguments) {
        ArrayList<String> tokens = new ArrayList<>(commandTokens.size() + replacementArguments.size());
        tokens.addAll(commandTokens.subList(0, sshIndex + 1));
        for (int i = sshIndex + 1; i < optionInsertionIndex; i++) {
            String token = commandTokens.get(i);
            if (!token.startsWith("-") || token.length() < 2) {
                tokens.add(token);
                continue;
            }
            boolean consumedValue = false;
            boolean removed = false;
            for (int position = 1; position < token.length(); position++) {
                char option = token.charAt(position);
                if (OPTIONS_WITH_VALUE.indexOf(option) < 0) continue;
                String value;
                if (position + 1 < token.length()) {
                    value = token.substring(position + 1);
                } else if (i + 1 < optionInsertionIndex) {
                    value = commandTokens.get(++i);
                    consumedValue = true;
                } else {
                    value = "";
                }
                if (option == 'o' && containsOptionIgnoreCase(optionNames, openSshOptionName(value))) {
                    removed = true;
                    int prefixLength = position - 1;
                    if (prefixLength > 0) tokens.add("-" + token.substring(1, position));
                } else if (!removed) {
                    tokens.add(token);
                    if (consumedValue) tokens.add(value);
                }
                break;
            }
            if (token.length() > 1 && !hasValueOption(token)) {
                tokens.add(token);
            }
        }
        tokens.addAll(replacementArguments);
        tokens.addAll(commandTokens.subList(optionInsertionIndex, commandTokens.size()));
        return renderTokens(tokens);
    }

    /** A stable, non-secret identifier used to isolate local OpenSSH control sockets. */
    @NonNull
    public String stableId() {
        byte[] input = renderBaseCommand().getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder out = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                out.append(Character.forDigit((digest[i] >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(digest[i] & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @NonNull
    public static String quoteShellToken(@NonNull String value) {
        if (value.isEmpty()) return "''";
        if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) return value;
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @NonNull
    private static String renderTokens(@NonNull List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) out.append(' ');
            out.append(quoteShellToken(token));
        }
        return out.toString();
    }

    private static boolean optionConsumesFollowingToken(@NonNull String token) {
        if (token.length() < 2) return false;
        for (int i = 1; i < token.length(); i++) {
            char option = token.charAt(i);
            int valueOption = OPTIONS_WITH_VALUE.indexOf(option);
            if (valueOption < 0) continue;
            return i == token.length() - 1;
        }
        return false;
    }

    private static boolean sshpassOptionConsumesFollowingToken(@NonNull String token) {
        if (token.startsWith("--")) {
            int equals = token.indexOf('=');
            String name = equals < 0 ? token : token.substring(0, equals);
            if ("--env-password".equals(name) || "--verbose".equals(name)) return false;
            if ("--password".equals(name) || "--file".equals(name) || "--fd".equals(name) ||
                "--prompt".equals(name)) {
                return equals < 0;
            }
            throw new IllegalArgumentException("Unsupported sshpass option " + token);
        }
        for (int i = 1; i < token.length(); i++) {
            char option = token.charAt(i);
            if (option == 'e' || option == 'v') continue;
            if (option == 'p' || option == 'f' || option == 'd' || option == 'P') {
                return i == token.length() - 1;
            }
            throw new IllegalArgumentException("Unsupported sshpass option " + token);
        }
        return false;
    }

    private static boolean isEnvironmentAssignment(@NonNull String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) return false;
        if (!Character.isLetter(token.charAt(0)) && token.charAt(0) != '_') return false;
        for (int i = 1; i < equals; i++) {
            char ch = token.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') return false;
        }
        return true;
    }

    @NonNull
    private static String openSshOptionName(@NonNull String option) {
        int equals = option.indexOf('=');
        int whitespace = -1;
        for (int i = 0; i < option.length(); i++) {
            if (Character.isWhitespace(option.charAt(i))) {
                whitespace = i;
                break;
            }
        }
        int end = equals < 0 ? option.length() : equals;
        if (whitespace >= 0 && whitespace < end) end = whitespace;
        return option.substring(0, end).trim();
    }

    @NonNull
    private static String openSshOptionValue(@NonNull String option) {
        int equals = option.indexOf('=');
        int whitespace = -1;
        for (int i = 0; i < option.length(); i++) {
            if (Character.isWhitespace(option.charAt(i))) {
                whitespace = i;
                break;
            }
        }
        int start = equals >= 0 ? equals + 1 : whitespace >= 0 ? whitespace + 1 : option.length();
        return start >= option.length() ? "" : option.substring(start).trim();
    }

    private static boolean containsOptionIgnoreCase(@NonNull Set<String> names,
                                                     @NonNull String optionName) {
        for (String name : names) {
            if (name != null && name.trim().equalsIgnoreCase(optionName)) return true;
        }
        return false;
    }

    private static boolean hasValueOption(@NonNull String token) {
        for (int position = 1; position < token.length(); position++) {
            if (OPTIONS_WITH_VALUE.indexOf(token.charAt(position)) >= 0) return true;
        }
        return false;
    }

    @NonNull
    private static String executableName(@NonNull String token) {
        int slash = token.lastIndexOf('/');
        String name = slash >= 0 ? token.substring(slash + 1) : token;
        return name.toLowerCase(Locale.ROOT);
    }

    @NonNull
    static List<String> splitLiteralShellWords(@NonNull String input) {
        ArrayList<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean tokenStarted = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\0' || ch == '\n' || ch == '\r') {
                throw new IllegalArgumentException("SSH command must be one line");
            }
            if (inSingle) {
                tokenStarted = true;
                if (ch == '\'') inSingle = false;
                else current.append(ch);
                continue;
            }
            if (inDouble) {
                tokenStarted = true;
                if (ch == '"') {
                    inDouble = false;
                } else if (ch == '\\') {
                    if (i + 1 >= input.length()) throw new IllegalArgumentException("Trailing escape");
                    char next = input.charAt(i + 1);
                    if (next == '$' || next == '`' || next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append(ch);
                    }
                } else if (ch == '$' || ch == '`') {
                    throw new IllegalArgumentException("Shell expansion is not supported in SSH profiles");
                } else {
                    current.append(ch);
                }
                continue;
            }

            if (Character.isWhitespace(ch)) {
                if (tokenStarted) {
                    words.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            if (ch == '#' && !tokenStarted) break;
            if (ch == '\'') {
                inSingle = true;
                tokenStarted = true;
            } else if (ch == '"') {
                inDouble = true;
                tokenStarted = true;
            } else if (ch == '\\') {
                if (i + 1 >= input.length()) throw new IllegalArgumentException("Trailing escape");
                char escaped = input.charAt(++i);
                if (escaped == '\0' || escaped == '\n' || escaped == '\r') {
                    throw new IllegalArgumentException("SSH command must be one line");
                }
                current.append(escaped);
                tokenStarted = true;
            } else if ("|&;<>()$`".indexOf(ch) >= 0 || "*?[]".indexOf(ch) >= 0) {
                throw new IllegalArgumentException("Shell operators and expansions are not supported in SSH profiles");
            } else {
                current.append(ch);
                tokenStarted = true;
            }
        }

        if (inSingle || inDouble) throw new IllegalArgumentException("Unterminated shell quote");
        if (tokenStarted) words.add(current.toString());
        return words;
    }
}
