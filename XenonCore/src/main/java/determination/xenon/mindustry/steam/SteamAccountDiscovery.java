/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package determination.xenon.mindustry.steam;

import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.windows.WinReg;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static determination.xenon.util.logging.Logger.LOG;

/// Discovers Steam accounts from the local Steam client data.
///
/// <p>Steam stores everything needed to reproduce a player's Mindustry UUID
/// on disk, so no Steamworks call or running Steam client is required:</p>
///
/// <ul>
///   <li>`config/loginusers.vdf` — SteamID64 keys with `AccountName`,
///       `PersonaName`, `AutoLogin`/`MostRecent` and `Timestamp`;</li>
///   <li>`config/config.vdf` — `…/Steam/Accounts/&lt;name&gt;/SteamID`
///       fallback for clients that never wrote `loginusers.vdf`;</li>
///   <li>`steamapps/appmanifest_1127400.acf` — `LastOwner` of the Mindustry
///       app manifest;</li>
///   <li>`userdata/&lt;accountId&gt;` — directories named after accounts that
///       signed in on this machine.</li>
/// </ul>
///
/// <p>The Steam client root is located through the Windows registry
/// (`HKCU\Software\Valve\Steam\SteamPath`) with the usual install paths as
/// fallback, plus the standard Linux and macOS locations.</p>
@NotNullByDefault
public final class SteamAccountDiscovery {

    /// SteamID64 of account id `0`, i.e. `76561197960265728`.
    public static final long STEAM_ID64_BASE = 76561197960265728L;

    /// Number of representable account ids (unsigned 32-bit).
    public static final long ACCOUNT_ID_RANGE = 1L << 32;

    /// Steam application id of Mindustry.
    public static final int MINDUSTRY_STEAM_APP_ID = 1127400;

    private static final String LOGIN_USERS_FILE = "config/loginusers.vdf";
    private static final String CONFIG_FILE = "config/config.vdf";
    private static final String APP_MANIFEST_FILE =
            "steamapps/appmanifest_" + MINDUSTRY_STEAM_APP_ID + ".acf";
    private static final String USER_DATA_DIR = "userdata";
    private static final String REGISTRY_KEY = "Software\\Valve\\Steam";
    private static final String REGISTRY_INSTALL_KEY = "SOFTWARE\\WOW6432Node\\Valve\\Steam";
    private static final int ACCOUNTS_SEARCH_DEPTH = 6;

    private SteamAccountDiscovery() {
    }

    /// Converts an individual SteamID64 into the 32-bit account id the game
    /// receives from `steamworks.SteamID#getAccountID()`.
    ///
    /// @param steamId64 the 64-bit individual Steam id
    /// @return the account id, negative when the unsigned value is above
    ///         `2^31` — exactly the bit pattern the game seeds `arc.math.Rand` with
    /// @throws IllegalArgumentException when `steamId64` is not an individual id
    public static int accountIdFromSteamId64(long steamId64) {
        if (steamId64 < STEAM_ID64_BASE || steamId64 >= STEAM_ID64_BASE + ACCOUNT_ID_RANGE) {
            throw new IllegalArgumentException("Not an individual SteamID64: " + steamId64);
        }
        return (int)(steamId64 - STEAM_ID64_BASE);
    }

    /// Inverse of [#accountIdFromSteamId64(long)].
    ///
    /// @param accountId the unsigned 32-bit account id
    /// @return the matching individual SteamID64
    /// @throws IllegalArgumentException when `accountId` is outside the
    ///         unsigned 32-bit range
    public static long steamId64FromAccountId(long accountId) {
        if (accountId < 0 || accountId >= ACCOUNT_ID_RANGE) {
            throw new IllegalArgumentException("Account id out of range: " + accountId);
        }
        return STEAM_ID64_BASE + accountId;
    }

    /// Locates existing Steam client roots on this machine.
    ///
    /// <p>On Windows the registry is consulted first because a client can be
    /// installed anywhere; the well-known `Program Files` locations and the
    /// `ProgramFiles(x86)` environment variable follow. Linux and macOS use
    /// the standard per-user locations.</p>
    ///
    /// @return existing, normalized, de-duplicated client roots
    public static @Unmodifiable List<Path> findClientRoots() {
        List<Path> candidates = new ArrayList<>();
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            addPath(candidates, queryRegistry(WinReg.HKEY.HKEY_CURRENT_USER, REGISTRY_KEY, "SteamPath"));
            addPath(candidates, queryRegistry(WinReg.HKEY.HKEY_LOCAL_MACHINE, REGISTRY_INSTALL_KEY, "InstallPath"));
            addPath(candidates, Path.of("C:\\Program Files (x86)\\Steam"));
            addEnvironmentPath(candidates, "ProgramFiles(x86)", "Steam");
            addEnvironmentPath(candidates, "ProgramFiles", "Steam");
        } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            addPath(candidates, Path.of(System.getProperty("user.home", "."),
                    "Library", "Application Support", "Steam"));
        } else {
            Path home = Path.of(System.getProperty("user.home", "."));
            addPath(candidates, home.resolve(".steam").resolve("steam"));
            addPath(candidates, home.resolve(".steam").resolve("root"));
            addPath(candidates, home.resolve(".local").resolve("share").resolve("Steam"));
            addPath(candidates, home.resolve(".var").resolve("app")
                    .resolve("com.valvesoftware.Steam").resolve("data").resolve("Steam"));
        }
        return uniqueDirectories(candidates);
    }

    /// Collects every Steam account visible in the local client data.
    ///
    /// @return accounts from all discovered client roots, richest entry per
    ///         account id, best candidate first
    public static @Unmodifiable List<SteamAccount> discoverAccounts() {
        Map<Integer, SteamAccount> accounts = new LinkedHashMap<>();
        for (Path clientRoot : findClientRoots()) {
            for (SteamAccount account : discoverAccounts(clientRoot)) {
                mergeAccount(accounts, account);
            }
        }
        return sortAccounts(accounts.values());
    }

    /// Collects Steam accounts recorded under one Steam client root.
    ///
    /// <p>`loginusers.vdf` is the primary source; `config.vdf`, the Mindustry
    /// app manifest and the `userdata` directories only add accounts that the
    /// primary source does not know about.</p>
    ///
    /// @param clientRoot the directory containing `config/` and `steamapps/`
    /// @return the accounts in preferred order, empty when the root is not a
    ///         Steam client or holds no account data
    public static @Unmodifiable List<SteamAccount> discoverAccounts(Path clientRoot) {
        if (clientRoot == null || !Files.isDirectory(clientRoot)) {
            return List.of();
        }

        Map<Integer, SteamAccount> accounts = new LinkedHashMap<>();
        for (SteamAccount account : readLoginUsers(clientRoot.resolve(LOGIN_USERS_FILE))) {
            mergeAccount(accounts, account);
        }
        for (SteamAccount account : readConfigAccounts(clientRoot.resolve(CONFIG_FILE))) {
            mergeAccount(accounts, account);
        }
        readLastOwner(clientRoot.resolve(APP_MANIFEST_FILE))
                .ifPresent(account -> mergeAccount(accounts, account));
        for (SteamAccount account : readUserData(clientRoot.resolve(USER_DATA_DIR))) {
            mergeAccount(accounts, account);
        }
        return sortAccounts(accounts.values());
    }

    /// Picks the account the UI should offer first.
    ///
    /// <p>The registry's `AutoLoginUser` name wins when it matches one of the
    /// discovered accounts, otherwise the highest-ranked account (auto-login
    /// flag, then Mindustry `LastOwner`, then newest sign-in) is used.</p>
    ///
    /// @param accounts accounts returned by [#discoverAccounts()]
    /// @return the preferred account, or empty when the list is empty
    public static Optional<SteamAccount> pickDefault(List<SteamAccount> accounts) {
        return pickDefault(accounts, findAutoLoginAccountName());
    }

    /// Testable variant of [#pickDefault(List)] with an explicit registry name.
    ///
    /// @param accounts             accounts to choose from
    /// @param autoLoginAccountName the Steam login name the client would
    ///                             auto-login, or `null`
    /// @return the preferred account, or empty when the list is empty
    static Optional<SteamAccount> pickDefault(List<SteamAccount> accounts,
                                              @Nullable String autoLoginAccountName) {
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        if (autoLoginAccountName != null && !autoLoginAccountName.isBlank()) {
            for (SteamAccount account : accounts) {
                if (autoLoginAccountName.equalsIgnoreCase(account.accountName())) {
                    return Optional.of(account);
                }
            }
        }
        return Optional.of(accounts.get(0));
    }

    /// Reads the Steam login name the client would sign in automatically.
    ///
    /// @return the registry `AutoLoginUser` value, or `null` when unavailable
    static @Nullable String findAutoLoginAccountName() {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            return null;
        }
        WinReg registry = WinReg.INSTANCE;
        if (registry == null) {
            return null;
        }
        try {
            Object value = registry.queryValue(WinReg.HKEY.HKEY_CURRENT_USER, REGISTRY_KEY, "AutoLoginUser");
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        } catch (RuntimeException ignored) {
            // A missing value or an unreadable hive simply means "unknown".
        }
        return null;
    }

    /// Parses `loginusers.vdf`, the richest account source.
    private static @Unmodifiable List<SteamAccount> readLoginUsers(Path file) {
        String text = readText(file);
        if (text == null) {
            return List.of();
        }
        try {
            SteamVdf.VdfObject users = SteamVdf.parse(text).object("users");
            if (users == null) {
                return List.of();
            }

            List<SteamAccount> accounts = new ArrayList<>();
            for (Map.Entry<String, SteamVdf.VdfValue> entry : users.values().entrySet()) {
                if (!(entry.getValue() instanceof SteamVdf.VdfObject user)) {
                    continue;
                }
                Long steamId64 = parseSteamId64(entry.getKey());
                if (steamId64 == null) {
                    continue;
                }
                accounts.add(new SteamAccount(
                        steamId64,
                        accountIdFromSteamId64(steamId64),
                        blankToNull(user.string("AccountName")),
                        blankToNull(user.string("PersonaName")),
                        isTrue(user.string("AutoLogin")) || isTrue(user.string("MostRecent")),
                        false,
                        parseLong(user.string("Timestamp"), 0L)));
            }
            return List.copyOf(accounts);
        } catch (RuntimeException ex) {
            LOG.warning("Failed to parse Steam login users " + file + ": " + ex.getMessage());
            return List.of();
        }
    }

    /// Parses the `Accounts` section of `config.vdf`.
    private static @Unmodifiable List<SteamAccount> readConfigAccounts(Path file) {
        String text = readText(file);
        if (text == null) {
            return List.of();
        }
        try {
            SteamVdf.VdfObject accountsNode = findAccountsNode(SteamVdf.parse(text));
            if (accountsNode == null) {
                return List.of();
            }

            List<SteamAccount> accounts = new ArrayList<>();
            for (Map.Entry<String, SteamVdf.VdfValue> entry : accountsNode.values().entrySet()) {
                if (!(entry.getValue() instanceof SteamVdf.VdfObject account)) {
                    continue;
                }
                Long steamId64 = parseSteamId64(account.string("SteamID"));
                if (steamId64 == null) {
                    continue;
                }
                accounts.add(new SteamAccount(
                        steamId64,
                        accountIdFromSteamId64(steamId64),
                        blankToNull(entry.getKey()),
                        null,
                        false,
                        false,
                        0L));
            }
            return List.copyOf(accounts);
        } catch (RuntimeException ex) {
            LOG.warning("Failed to parse Steam config " + file + ": " + ex.getMessage());
            return List.of();
        }
    }

    /// Finds the `…/Steam/Accounts` object, preferring the canonical path.
    private static @Nullable SteamVdf.VdfObject findAccountsNode(SteamVdf.VdfObject root) {
        SteamVdf.VdfObject steam = root;
        for (String key : List.of("InstallConfigStore", "Software", "Valve", "Steam")) {
            if (steam == null) {
                break;
            }
            steam = steam.object(key);
        }
        if (steam != null && steam.object("Accounts") != null) {
            return steam.object("Accounts");
        }
        return findAccountsNodeRecursive(root, ACCOUNTS_SEARCH_DEPTH);
    }

    /// Depth-limited fallback search for a node that looks like an account table.
    private static @Nullable SteamVdf.VdfObject findAccountsNodeRecursive(SteamVdf.VdfObject node, int depth) {
        if (depth <= 0) {
            return null;
        }
        for (Map.Entry<String, SteamVdf.VdfValue> entry : node.values().entrySet()) {
            if (!(entry.getValue() instanceof SteamVdf.VdfObject child)) {
                continue;
            }
            if ("Accounts".equals(entry.getKey()) && holdsAccountEntries(child)) {
                return child;
            }
            SteamVdf.VdfObject nested = findAccountsNodeRecursive(child, depth - 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /// Whether an `Accounts`-shaped object contains at least one `SteamID`.
    private static boolean holdsAccountEntries(SteamVdf.VdfObject node) {
        for (SteamVdf.VdfValue value : node.values().values()) {
            if (value instanceof SteamVdf.VdfObject account && account.string("SteamID") != null) {
                return true;
            }
        }
        return false;
    }

    /// Reads the Mindustry app manifest `LastOwner`.
    private static Optional<SteamAccount> readLastOwner(Path file) {
        String text = readText(file);
        if (text == null) {
            return Optional.empty();
        }
        try {
            SteamVdf.VdfObject appState = SteamVdf.parse(text).object("AppState");
            if (appState == null) {
                return Optional.empty();
            }
            Long steamId64 = parseSteamId64(appState.string("LastOwner"));
            if (steamId64 == null) {
                return Optional.empty();
            }
            return Optional.of(new SteamAccount(
                    steamId64,
                    accountIdFromSteamId64(steamId64),
                    null,
                    null,
                    false,
                    true,
                    0L));
        } catch (RuntimeException ex) {
            LOG.warning("Failed to parse Steam app manifest " + file + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    /// Scans `userdata/<accountId>` directories, the last-resort source.
    private static @Unmodifiable List<SteamAccount> readUserData(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<SteamAccount> accounts = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || entry.getFileName() == null) {
                    continue;
                }
                long accountId = parseLong(entry.getFileName().toString(), -1L);
                if (accountId <= 0 || accountId >= ACCOUNT_ID_RANGE) {
                    continue;
                }
                long steamId64 = steamId64FromAccountId(accountId);
                accounts.add(new SteamAccount(steamId64, (int)accountId, null, null, false, false, 0L));
            }
        } catch (Exception ex) {
            LOG.warning("Failed to list Steam user data " + directory + ": " + ex.getMessage());
            return List.of();
        }
        return List.copyOf(accounts);
    }

    /// Merges one account into the id-keyed result map.
    private static void mergeAccount(Map<Integer, SteamAccount> accounts, SteamAccount account) {
        SteamAccount existing = accounts.get(account.accountId());
        accounts.put(account.accountId(), existing == null ? account : mergeAccount(existing, account));
    }

    /// Combines two records of the same account, keeping the richer fields.
    private static SteamAccount mergeAccount(SteamAccount first, SteamAccount second) {
        return new SteamAccount(
                first.steamId64(),
                first.accountId(),
                first.accountName() != null ? first.accountName() : second.accountName(),
                first.personaName() != null ? first.personaName() : second.personaName(),
                first.autoLogin() || second.autoLogin(),
                first.lastOwner() || second.lastOwner(),
                Math.max(first.lastLoginTimestamp(), second.lastLoginTimestamp()));
    }

    /// Orders accounts: auto-login, then Mindustry last-owner, then newest sign-in.
    private static @Unmodifiable List<SteamAccount> sortAccounts(Collection<SteamAccount> accounts) {
        List<SteamAccount> sorted = new ArrayList<>(accounts);
        sorted.sort(Comparator
                .comparing(SteamAccount::autoLogin, Comparator.reverseOrder())
                .thenComparing(SteamAccount::lastOwner, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(SteamAccount::lastLoginTimestamp).reversed())
                .thenComparingLong(SteamAccount::steamId64));
        return List.copyOf(sorted);
    }

    /// Reads a file as UTF-8, tolerating malformed bytes.
    private static @Nullable String readText(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            LOG.warning("Failed to read Steam data " + file + ": " + ex.getMessage());
            return null;
        }
    }

    /// Parses an individual SteamID64, rejecting group ids and empty values.
    private static @Nullable Long parseSteamId64(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long value = parseLong(raw.trim(), -1L);
        if (value < STEAM_ID64_BASE || value >= STEAM_ID64_BASE + ACCOUNT_ID_RANGE) {
            return null;
        }
        return value;
    }

    /// Parses a decimal number, returning `fallback` on any failure.
    private static long parseLong(@Nullable String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /// Whether a VDF boolean flag is set to `"1"`.
    private static boolean isTrue(@Nullable String raw) {
        return "1".equals(raw == null ? null : raw.trim());
    }

    /// Converts a blank string into `null`.
    private static @Nullable String blankToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /// Reads a Windows registry string value, returning `null` when absent.
    private static @Nullable Path queryRegistry(WinReg.HKEY root, String key, String valueName) {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
            return null;
        }
        WinReg registry = WinReg.INSTANCE;
        if (registry == null) {
            return null;
        }
        try {
            Object value = registry.queryValue(root, key, valueName);
            if (value instanceof String text && !text.isBlank()) {
                return Path.of(text);
            }
        } catch (RuntimeException ignored) {
            // A missing value or an unreadable hive simply means "unknown".
        }
        return null;
    }

    /// Adds `path` when it is a non-null directory.
    private static void addPath(List<Path> paths, @Nullable Path path) {
        if (path != null && Files.isDirectory(path)) {
            paths.add(path);
        }
    }

    /// Adds `<environment variable>/<child>` when that directory exists.
    private static void addEnvironmentPath(List<Path> paths, String variable, String child) {
        String value = System.getenv(variable);
        if (value != null && !value.isBlank()) {
            addPath(paths, Path.of(value).resolve(child));
        }
    }

    /// Normalizes and de-duplicates directory paths.
    private static @Unmodifiable List<Path> uniqueDirectories(List<Path> paths) {
        Map<String, Path> unique = new LinkedHashMap<>();
        for (Path path : paths) {
            Path normalized = path.toAbsolutePath().normalize();
            unique.putIfAbsent(normalized.toString().toLowerCase(Locale.ROOT), normalized);
        }
        return List.copyOf(unique.values());
    }
}
