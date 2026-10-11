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

import determination.xenon.mindustry.uuid.UuidGenerator;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests discovery of Steam accounts from local Steam client data.
@NotNullByDefault
public final class SteamAccountDiscoveryTest {

    /// SteamID64 of account id 42.
    private static final long ACCOUNT_42 = SteamAccountDiscovery.STEAM_ID64_BASE + 42;
    /// SteamID64 of account id 84.
    private static final long ACCOUNT_84 = SteamAccountDiscovery.STEAM_ID64_BASE + 84;

    /// `loginusers.vdf` supplies account names, flags and the derived Mindustry UID.
    @Test
    public void readsLoginUsersAccounts(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        write(steam.resolve("config/loginusers.vdf"), """
                "users"
                {
                	"%d"
                	{
                		"AccountName"		"mainaccount"
                		"PersonaName"		"Main Persona"
                		"RememberPassword"		"1"
                		"AutoLogin"		"1"
                		"Timestamp"		"1700000000"
                	}
                	"%d"
                	{
                		"AccountName"		"secondaccount"
                		"PersonaName"		"Second Persona"
                		"RememberPassword"		"0"
                		"AutoLogin"		"0"
                		"Timestamp"		"1800000000"
                	}
                }
                """.formatted(ACCOUNT_42, ACCOUNT_84));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(2, accounts.size());

        // Auto-login wins over the newer sign-in timestamp.
        SteamAccount main = accounts.get(0);
        assertEquals(42, main.accountId());
        assertEquals(ACCOUNT_42, main.steamId64());
        assertEquals("mainaccount", main.accountName());
        assertEquals("Main Persona", main.personaName());
        assertTrue(main.autoLogin());
        assertFalse(main.lastOwner());
        assertEquals(1700000000L, main.lastLoginTimestamp());

        // Known vector from the shipped Steam build (build 159.7).
        assertEquals("MVBa94jYF3E=", main.mindustryUid());
        assertEquals(UuidGenerator.fromSteamAccountId(42), main.mindustryUid());

        SteamAccount second = accounts.get(1);
        assertEquals(84, second.accountId());
        assertFalse(second.autoLogin());
        assertEquals("Second Persona", second.displayName());
    }

    /// `pickDefault` prefers the registry auto-login name, then the ranked head.
    @Test
    public void picksDefaultAccount(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        write(steam.resolve("config/loginusers.vdf"), """
                "users"
                {
                	"%d"
                	{
                		"AccountName"		"mainaccount"
                		"PersonaName"		"Main Persona"
                		"AutoLogin"		"1"
                		"Timestamp"		"1700000000"
                	}
                	"%d"
                	{
                		"AccountName"		"secondaccount"
                		"PersonaName"		"Second Persona"
                		"AutoLogin"		"0"
                		"Timestamp"		"1800000000"
                	}
                }
                """.formatted(ACCOUNT_42, ACCOUNT_84));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(42, SteamAccountDiscovery.pickDefault(accounts, null).orElseThrow().accountId());
        assertEquals(84, SteamAccountDiscovery.pickDefault(accounts, "secondaccount")
                .orElseThrow().accountId());
        assertEquals(84, SteamAccountDiscovery.pickDefault(accounts, "SECONDACCOUNT")
                .orElseThrow().accountId());
        assertTrue(SteamAccountDiscovery.pickDefault(List.of(), null).isEmpty());
    }

    /// `config.vdf` is used when no `loginusers.vdf` exists.
    @Test
    public void fallsBackToConfigAccounts(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        write(steam.resolve("config/config.vdf"), """
                "InstallConfigStore"
                {
                	"Software"
                	{
                		"Valve"
                		{
                			"Steam"
                			{
                				"Accounts"
                				{
                					"cfgaccount"
                					{
                						"SteamID"		"%d"
                					}
                				}
                			}
                		}
                	}
                }
                """.formatted(ACCOUNT_42));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(1, accounts.size());
        SteamAccount account = accounts.get(0);
        assertEquals(42, account.accountId());
        assertEquals("cfgaccount", account.accountName());
        assertNull(account.personaName());
        assertFalse(account.autoLogin());
        assertEquals("MVBa94jYF3E=", account.mindustryUid());
    }

    /// `userdata/<accountId>` directories are the last-resort source.
    @Test
    public void fallsBackToUserDataDirectories(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        Files.createDirectories(steam.resolve("userdata/123456789"));
        Files.createDirectories(steam.resolve("userdata/notanaccount"));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(1, accounts.size());
        SteamAccount account = accounts.get(0);
        assertEquals(123456789, account.accountId());
        assertEquals(SteamAccountDiscovery.steamId64FromAccountId(123456789), account.steamId64());
        assertNull(account.accountName());
        // Known vector from the shipped Steam build (build 159.7).
        assertEquals("n1IU8pbuGZ4=", account.mindustryUid());
    }

    /// The Mindustry app manifest `LastOwner` is merged in as an extra account.
    @Test
    public void mergesAppManifestLastOwner(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        write(steam.resolve("config/loginusers.vdf"), """
                "users"
                {
                	"%d"
                	{
                		"AccountName"		"mainaccount"
                		"PersonaName"		"Main Persona"
                		"AutoLogin"		"1"
                		"Timestamp"		"1700000000"
                	}
                }
                """.formatted(ACCOUNT_42));
        write(steam.resolve("steamapps/appmanifest_1127400.acf"), """
                "AppState"
                {
                	"appid"		"1127400"
                	"name"		"Mindustry"
                	"LastOwner"		"%d"
                }
                """.formatted(ACCOUNT_84));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(2, accounts.size());
        assertEquals(42, accounts.get(0).accountId());

        SteamAccount owner = accounts.get(1);
        assertEquals(84, owner.accountId());
        assertTrue(owner.lastOwner());
        assertFalse(owner.autoLogin());
        assertNull(owner.accountName());
    }

    /// The same account seen in several sources is merged into one richer entry.
    @Test
    public void mergesDuplicateAccountsAcrossSources(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        write(steam.resolve("config/loginusers.vdf"), """
                "users"
                {
                	"%d"
                	{
                		"AccountName"		"mainaccount"
                		"PersonaName"		"Main Persona"
                		"AutoLogin"		"1"
                		"Timestamp"		"1700000000"
                	}
                }
                """.formatted(ACCOUNT_42));
        write(steam.resolve("config/config.vdf"), """
                "InstallConfigStore"
                {
                	"Software"
                	{
                		"Valve"
                		{
                			"Steam"
                			{
                				"Accounts"
                				{
                					"mainaccount"
                					{
                						"SteamID"		"%d"
                					}
                				}
                			}
                		}
                	}
                }
                """.formatted(ACCOUNT_42));
        Files.createDirectories(steam.resolve("userdata/42"));
        write(steam.resolve("steamapps/appmanifest_1127400.acf"), """
                "AppState"
                {
                	"appid"		"1127400"
                	"LastOwner"		"%d"
                }
                """.formatted(ACCOUNT_42));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(1, accounts.size());
        SteamAccount account = accounts.get(0);
        assertEquals("mainaccount", account.accountName());
        assertEquals("Main Persona", account.personaName());
        assertTrue(account.autoLogin());
        assertTrue(account.lastOwner());
        assertEquals(1700000000L, account.lastLoginTimestamp());
    }

    /// Non-individual Steam ids are rejected instead of silently producing garbage.
    @Test
    public void rejectsNonIndividualSteamIds() {
        assertEquals(0, SteamAccountDiscovery.accountIdFromSteamId64(SteamAccountDiscovery.STEAM_ID64_BASE));
        assertEquals(42, SteamAccountDiscovery.accountIdFromSteamId64(ACCOUNT_42));
        assertEquals(-1, SteamAccountDiscovery.accountIdFromSteamId64(
                SteamAccountDiscovery.STEAM_ID64_BASE + SteamAccountDiscovery.ACCOUNT_ID_RANGE - 1));

        assertThrows(IllegalArgumentException.class,
                () -> SteamAccountDiscovery.accountIdFromSteamId64(SteamAccountDiscovery.STEAM_ID64_BASE - 1));
        assertThrows(IllegalArgumentException.class,
                () -> SteamAccountDiscovery.accountIdFromSteamId64(
                        SteamAccountDiscovery.STEAM_ID64_BASE + SteamAccountDiscovery.ACCOUNT_ID_RANGE));
        assertThrows(IllegalArgumentException.class,
                () -> SteamAccountDiscovery.steamId64FromAccountId(-1));
        assertThrows(IllegalArgumentException.class,
                () -> SteamAccountDiscovery.steamId64FromAccountId(SteamAccountDiscovery.ACCOUNT_ID_RANGE));

        assertEquals(SteamAccountDiscovery.STEAM_ID64_BASE,
                SteamAccountDiscovery.steamId64FromAccountId(0));
        assertEquals(SteamAccountDiscovery.STEAM_ID64_BASE + 42,
                SteamAccountDiscovery.steamId64FromAccountId(42));
    }

    /// A directory without Steam data yields no accounts instead of throwing.
    @Test
    public void ignoresDirectoriesWithoutSteamData(@TempDir Path tempDir) throws IOException {
        Path empty = Files.createDirectories(tempDir.resolve("empty"));
        assertTrue(SteamAccountDiscovery.discoverAccounts(empty).isEmpty());
        assertTrue(SteamAccountDiscovery.discoverAccounts(tempDir.resolve("missing")).isEmpty());
    }

    /// A broken `loginusers.vdf` does not abort discovery of the other sources.
    @Test
    public void survivesMalformedLoginUsersFile(@TempDir Path tempDir) throws IOException {
        Path steam = tempDir.resolve("Steam");
        write(steam.resolve("config/loginusers.vdf"), "\"users\" { \"123\" {");
        Files.createDirectories(steam.resolve("userdata/42"));

        List<SteamAccount> accounts = SteamAccountDiscovery.discoverAccounts(steam);

        assertEquals(1, accounts.size());
        assertEquals(42, accounts.get(0).accountId());
    }

    /// Writes a fixture file, creating parent directories as needed.
    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
