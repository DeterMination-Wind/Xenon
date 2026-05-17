/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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
package determination.xenon.ui;

import determination.xenon.auth.*;
import determination.xenon.ui.account.ClassicAccountLoginDialog;
import determination.xenon.ui.account.MicrosoftAccountLoginPane;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static determination.xenon.ui.FXUtils.runInFX;

public final class DialogController {
    private DialogController() {
    }

    public static AuthInfo logIn(Account account) throws CancellationException, AuthenticationException, InterruptedException {
        if (account instanceof ClassicAccount) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<AuthInfo> res = new AtomicReference<>(null);
            runInFX(() -> {
                ClassicAccountLoginDialog pane = new ClassicAccountLoginDialog((ClassicAccount) account, it -> {
                    res.set(it);
                    latch.countDown();
                }, latch::countDown);
                Controllers.dialog(pane);
            });
            latch.await();
            return Optional.ofNullable(res.get()).orElseThrow(CancellationException::new);
        } else if (account instanceof OAuthAccount) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<AuthInfo> res = new AtomicReference<>(null);
            runInFX(() -> {
                MicrosoftAccountLoginPane pane = new MicrosoftAccountLoginPane(account, it -> {
                    res.set(it);
                    latch.countDown();
                }, latch::countDown, false);
                Controllers.dialog(pane);
            });
            latch.await();
            return Optional.ofNullable(res.get()).orElseThrow(CancellationException::new);
        }
        return account.logIn();
    }
}
