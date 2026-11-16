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
package determination.xenon.setting;

import determination.xenon.auth.Account;
import determination.xenon.auth.offline.OfflineAccount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests the detached selection rule used by {@link Accounts}: the current
/// selection survives while it is still listed, otherwise the first account
/// takes over, and only an empty list may leave the launcher without a
/// selection.
///
/// The test never touches the JavaFX toolkit, `ConfigHolder` or
/// {@link Accounts#init()}; it only loads the class to reach the static helper.
public final class AccountsSelectionTest {

    private static final Account A = OfflineAccount.fromUsername("a");
    private static final Account B = OfflineAccount.fromUsername("b");
    private static final Account C = OfflineAccount.fromUsername("c");

    @Test
    public void testKeepCurrentWhenStillPresent() {
        List<Account> accounts = new ArrayList<>(List.of(A, B, C));
        assertSame(B, Accounts.resolveSelection(accounts, B));
    }

    @Test
    public void testFallBackToFirstWhenCurrentRemoved() {
        List<Account> accounts = new ArrayList<>(List.of(A, B, C));
        Account removed = OfflineAccount.fromUsername("d");
        assertSame(A, Accounts.resolveSelection(accounts, removed));
    }

    @Test
    public void testNoSelectionWhenListIsEmpty() {
        assertNull(Accounts.resolveSelection(new ArrayList<>(), B));
        assertNull(Accounts.resolveSelection(new ArrayList<>(), null));
    }

    @Test
    public void testFallBackToFirstWhenCurrentIsNull() {
        List<Account> accounts = new ArrayList<>(List.of(B, C));
        assertSame(B, Accounts.resolveSelection(accounts, null));
    }
}
