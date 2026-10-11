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
package determination.xenon.ui.account;

import determination.xenon.JavaFXLauncher;
import determination.xenon.setting.Accounts;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// JavaFX smoke test for the offline account dialog and its Steam import entry.
@NotNullByDefault
public final class CreateAccountPaneSmokeTest {

    /// Starts the JavaFX toolkit once for the test class.
    @BeforeAll
    public static void startToolkit() {
        JavaFXLauncher.start();
    }

    /// The offline account dialog builds and exposes the Steam import button.
    @Test
    public void offlinePaneExposesSteamImport() throws Exception {
        Assumptions.assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");

        List<String> buttons = onFx(() -> {
            CreateAccountPane pane = new CreateAccountPane(Accounts.FACTORY_OFFLINE);
            List<String> texts = new ArrayList<>();
            collectButtons(pane, texts);
            return texts;
        });

        assertTrue(buttons.stream().anyMatch(text -> text.contains("Steam")),
                "missing Steam import button: " + buttons);
    }

    /// Collects the labels of every button below `node`.
    private static void collectButtons(Node node, List<String> texts) {
        if (node instanceof ButtonBase button && button.getText() != null && !button.getText().isBlank()) {
            texts.add(button.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectButtons(child, texts);
            }
        }
    }

    /// Runs a producer on the JavaFX thread and waits for its result.
    private static <T> T onFx(Callable<T> supplier) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(supplier.call());
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "JavaFX task did not finish");
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        assertNotNull(result.get());
        return result.get();
    }
}
