/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon.setting;

import javafx.beans.Observable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import determination.xenon.Metadata;
import determination.xenon.auth.Account;
import determination.xenon.auth.offline.OfflineAccount;
import determination.xenon.util.FileSaver;
import determination.xenon.util.gson.UUIDTypeAdapter;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static javafx.collections.FXCollections.observableArrayList;
import static determination.xenon.setting.ConfigHolder.config;
import static determination.xenon.ui.FXUtils.onInvalidating;
import static determination.xenon.util.Lang.tryCast;
import static determination.xenon.util.logging.Logger.LOG;

/// The local identity list.
///
/// Xenon keeps Mindustry UID profiles here: one offline account per nickname.
/// The selected account feeds [determination.xenon.mindustry.CurrentPlayerProfile],
/// which turns it into the UUID and nickname passed to the game at launch.
public final class Accounts {
    private Accounts() {
    }

    private static final String GLOBAL_PREFIX = "$GLOBAL:";
    private static final ObservableList<Map<Object, Object>> globalAccountStorages = FXCollections.observableArrayList();

    private static final ObservableList<Account> accounts = observableArrayList(account -> new Observable[]{account});
    private static final ObjectProperty<Account> selectedAccount = new SimpleObjectProperty<>(Accounts.class, "selectedAccount");

    /// True if {@link #init()} hasn't been called.
    private static boolean initialized = false;

    private static void updateAccountStorages() {
        // Don't update the underlying storage before data loading is completed,
        // otherwise it might cause data loss.
        if (!initialized)
            return;

        ArrayList<Map<Object, Object>> global = new ArrayList<>();
        ArrayList<Map<Object, Object>> portable = new ArrayList<>();

        for (Account account : accounts) {
            Map<Object, Object> storage = account.toStorage();
            storage.put("type", "offline");
            if (account.isPortable())
                portable.add(storage);
            else
                global.add(storage);
        }

        if (!global.equals(globalAccountStorages))
            globalAccountStorages.setAll(global);
        if (!portable.equals(config().getAccountStorages()))
            config().getAccountStorages().setAll(portable);
    }

    private static void loadGlobalAccountStorages() {
        Path globalAccountsFile = Metadata.XENON_GLOBAL_DIRECTORY.resolve("accounts.json");
        if (Files.exists(globalAccountsFile)) {
            try (Reader reader = Files.newBufferedReader(globalAccountsFile)) {
                java.util.List<Map<Object, Object>> loaded = Config.CONFIG_GSON.fromJson(reader,
                        new com.google.gson.reflect.TypeToken<java.util.List<Map<Object, Object>>>() {}.getType());
                if (loaded != null) {
                    globalAccountStorages.setAll(loaded);
                }
            } catch (Throwable e) {
                LOG.warning("Failed to load global accounts", e);
            }
        }

        globalAccountStorages.addListener(onInvalidating(() ->
                FileSaver.save(globalAccountsFile, Config.CONFIG_GSON.toJson(globalAccountStorages))));
    }

    private static Account parseAccount(Map<Object, Object> storage) {
        String type = String.valueOf(storage.get("type"));
        if (!"offline".equals(type)) {
            LOG.warning("Dropping unsupported account type: " + type);
            return null;
        }

        try {
            String username = tryCast(storage.get("username"), String.class)
                    .orElseThrow(() -> new IllegalStateException("Account storage is missing a username"));
            UUID uuid = tryCast(storage.get("uuid"), String.class)
                    .map(UUIDTypeAdapter::fromString)
                    .orElseGet(() -> OfflineAccount.uuidFor(username));
            return new OfflineAccount(username, uuid);
        } catch (Exception e) {
            LOG.warning("Failed to load account: " + storage, e);
            return null;
        }
    }

    /// Called when it's ready to load accounts from {@link ConfigHolder#config()}.
    static void init() {
        if (initialized)
            throw new IllegalStateException("Already initialized");

        loadGlobalAccountStorages();

        Account selected = null;
        for (Map<Object, Object> storage : config().getAccountStorages()) {
            Account account = parseAccount(storage);
            if (account != null) {
                account.setPortable(true);
                accounts.add(account);
                if (Boolean.TRUE.equals(storage.get("selected"))) {
                    selected = account;
                }
            }
        }

        for (Map<Object, Object> storage : globalAccountStorages) {
            Account account = parseAccount(storage);
            if (account != null) {
                accounts.add(account);
            }
        }

        String selectedAccountIdentifier = config().getSelectedAccount();
        if (selected == null && selectedAccountIdentifier != null) {
            boolean portable = true;
            if (selectedAccountIdentifier.startsWith(GLOBAL_PREFIX)) {
                portable = false;
                selectedAccountIdentifier = selectedAccountIdentifier.substring(GLOBAL_PREFIX.length());
            }

            selected = findAccountByIdentifier(selectedAccountIdentifier, portable);
            if (selected == null) {
                // Xenon <= 2.0 stored "username:username"; the identifier is
                // now "username:uuid". Keep the old selection instead of
                // silently falling back to the first account.
                selected = findAccountByLegacyIdentifier(selectedAccountIdentifier, portable);
                if (selected != null) {
                    config().setSelectedAccount(
                            (selected.isPortable() ? "" : GLOBAL_PREFIX) + selected.getIdentifier());
                }
            }
        }

        if (selected == null && !accounts.isEmpty()) {
            selected = accounts.get(0);
        }

        selectedAccount.set(selected);

        accounts.addListener(onInvalidating(Accounts::updateAccountStorages));
        selectedAccount.addListener((observable, oldValue, newValue) -> {
            if (!initialized) {
                return;
            }

            updateAccountStorages();
            if (newValue == null) {
                config().setSelectedAccount("");
            } else {
                config().setSelectedAccount((newValue.isPortable() ? "" : GLOBAL_PREFIX) + newValue.getIdentifier());
            }
        });

        initialized = true;
    }

    /// Finds the account whose identifier matches, preferring the storage the
    /// configured id came from.
    @Nullable
    private static Account findAccountByIdentifier(String identifier, boolean portable) {
        Account fallback = null;
        for (Account account : accounts) {
            if (!identifier.equals(account.getIdentifier()))
                continue;
            if (portable == account.isPortable())
                return account;
            if (fallback == null)
                fallback = account;
        }
        return fallback;
    }

    /// Matches the identifier format written before accounts stored the UUID.
    @Nullable
    private static Account findAccountByLegacyIdentifier(String identifier, boolean portable) {
        Account fallback = null;
        for (Account account : accounts) {
            String legacyIdentifier = account.getUsername() + ":" + account.getUsername();
            if (!identifier.equals(legacyIdentifier))
                continue;
            if (portable == account.isPortable())
                return account;
            if (fallback == null)
                fallback = account;
        }
        return fallback;
    }

    public static ObservableList<Account> getAccounts() {
        return accounts;
    }

    public static ObjectProperty<Account> selectedAccountProperty() {
        return selectedAccount;
    }

    public static Account getSelectedAccount() {
        return selectedAccount.get();
    }

    public static void setSelectedAccount(Account account) {
        selectedAccount.set(account);
    }

    /// Creates and registers an offline account with a UUID derived from the nickname.
    public static OfflineAccount createAccount(String username) {
        return createAccount(username, null);
    }

    /// Creates and registers an offline account, optionally reusing an explicit UUID.
    public static OfflineAccount createAccount(String username, @Nullable UUID uuid) {
        OfflineAccount account = uuid == null
                ? OfflineAccount.fromUsername(username)
                : new OfflineAccount(username, uuid);
        accounts.add(account);
        setSelectedAccount(account);
        return account;
    }

    /// Human-readable error text for account operations.
    public static String localizeErrorMessage(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
