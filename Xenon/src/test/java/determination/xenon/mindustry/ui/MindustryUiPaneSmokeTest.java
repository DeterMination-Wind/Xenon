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
package determination.xenon.mindustry.ui;

import determination.xenon.JavaFXLauncher;
import determination.xenon.mindustry.uuid.MindustrySettingsBackups;
import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import javafx.application.Platform;
import javafx.scene.control.TableView;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JavaFX smoke tests for the settings editor and mod list pages.
///
/// The tests only exercise construction and the asynchronous load path; cell
/// rendering and user input are covered by the manual checklist in the release
/// process.
@NotNullByDefault
public final class MindustryUiPaneSmokeTest {

    /// Starts the JavaFX toolkit once for the test class.
    @BeforeAll
    public static void startToolkit() {
        JavaFXLauncher.start();
    }

    /// The settings editor lists every entry and leaves the page editable.
    @Test
    public void settingsEditorLoadsEntries(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("mod-alpha-enabled", true, "name", "Xenon"));

        TableView<?> table = onFx(() -> {
            MindustrySettingsEditorPane pane = new MindustrySettingsEditorPane(tempDir, null, false);
            return (TableView<?>) pane.getCenter();
        });

        waitFor(() -> table.getItems().size() == 2);
        waitFor(() -> !table.isDisable());
    }

    /// Saving writes the edited key and creates a backup first.
    @Test
    public void settingsEditorSaveCreatesBackup(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("mod-alpha-enabled", true));
        MindustrySettingsEditorPane pane = onFx(() ->
                new MindustrySettingsEditorPane(tempDir, null, false));
        waitFor(() -> ((TableView<?>) pane.getCenter()).getItems().size() == 1);

        onFx(() -> {
            pane.applyEditAndSaveForTests("mod-alpha-enabled", false);
            return true;
        });

        waitFor(() -> !MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));
        assertEquals(1, MindustrySettingsBackups.list(tempDir).size());
    }

    /// A corrupt settings.bin switches the editor to read-only instead of showing an empty table.
    @Test
    public void settingsEditorBecomesReadOnlyOnParseFailure(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(MindustrySettingsBin.FILE_NAME), "not a settings file");

        TableView<?> table = onFx(() -> {
            MindustrySettingsEditorPane pane = new MindustrySettingsEditorPane(tempDir, null, false);
            return (TableView<?>) pane.getCenter();
        });

        waitFor(table::isDisable);
        waitFor(() -> table.getItems().isEmpty());
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

    /// Polls a condition that must be evaluated on the JavaFX thread.
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (onFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Condition was not met within 10 seconds");
    }
}
