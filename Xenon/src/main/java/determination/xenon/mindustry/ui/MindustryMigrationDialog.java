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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXDialogLayout;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.migrate.InstanceDataMigration;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.DialogCloseEvent;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Dialog that copies selected content from another instance into the
/// instance it was opened for.
///
/// The user picks a source instance, the content categories, and whether
/// same-named files may be replaced; the copy then runs on the IO scheduler.
@NotNullByDefault
public final class MindustryMigrationDialog extends JFXDialogLayout {

    /// The stats keys shown as checkboxes, in display order.
    private static final List<InstanceDataMigration.Category> CATEGORY_ORDER = List.of(
            InstanceDataMigration.Category.SAVES,
            InstanceDataMigration.Category.MODS,
            InstanceDataMigration.Category.SCHEMATICS,
            InstanceDataMigration.Category.MAPS,
            InstanceDataMigration.Category.SETTINGS,
            InstanceDataMigration.Category.PLAYTIME);

    /// Creates the dialog for one target instance.
    ///
    /// @param target the instance that receives the copied data
    public MindustryMigrationDialog(MindustryVersion target) {
        setHeading(new Label(i18n("xenon.mindustry.migrate.title")));

        XenonGameRepository repository = MindustryImportFlow.repositoryForVersion(target);
        List<MindustryVersion> sources = repository.all().stream()
                .filter(version -> !version.getId().equals(target.getId()))
                .toList();

        VBox body = new VBox(10);
        body.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));

        Label sourceLabel = new Label(i18n("xenon.mindustry.migrate.source"));
        JFXComboBox<MindustryVersion> sourceBox = new JFXComboBox<>();
        sourceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(@Nullable MindustryVersion version) {
                return version == null ? "" : version.getName();
            }

            @Override
            public MindustryVersion fromString(String string) {
                return null;
            }
        });
        sourceBox.getItems().setAll(sources);
        if (!sources.isEmpty()) {
            sourceBox.getSelectionModel().selectFirst();
        }
        sourceBox.setMaxWidth(Double.MAX_VALUE);

        Map<InstanceDataMigration.Category, JFXCheckBox> checks =
                new EnumMap<>(InstanceDataMigration.Category.class);
        for (InstanceDataMigration.Category category : CATEGORY_ORDER) {
            JFXCheckBox check = new JFXCheckBox(i18n("xenon.mindustry.migrate.category."
                    + category.name().toLowerCase(java.util.Locale.ROOT)));
            check.setSelected(true);
            checks.put(category, check);
        }
        // settings.bin carries the player name and UUID, so it starts off.
        checks.get(InstanceDataMigration.Category.SETTINGS).setSelected(false);

        JFXCheckBox overwrite = new JFXCheckBox(i18n("xenon.mindustry.migrate.overwrite"));

        Label hint = new Label(i18n("xenon.mindustry.migrate.hint"));
        hint.setWrapText(true);

        Label status = new Label();
        status.setWrapText(true);

        VBox categoryBox = new VBox(6);
        categoryBox.getChildren().addAll(checks.values());
        categoryBox.getChildren().add(overwrite);

        body.getChildren().addAll(sourceLabel, sourceBox, categoryBox, hint, status);

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.setFitToWidth(true);
        setBody(scrollPane);

        JFXButton run = new JFXButton(i18n("xenon.mindustry.migrate.run"));
        run.getStyleClass().add("dialog-accept");
        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        if (sources.isEmpty()) {
            status.setText(i18n("xenon.mindustry.migrate.empty"));
            run.setDisable(true);
        } else {
            run.setOnAction(e -> runMigration(target, repository, sourceBox.getValue(),
                    collect(checks), overwrite.isSelected(), run, status));
        }

        setActions(run, cancel);
    }

    /// Collects the selected categories.
    private static EnumSet<InstanceDataMigration.Category> collect(
            Map<InstanceDataMigration.Category, JFXCheckBox> checks) {
        EnumSet<InstanceDataMigration.Category> selected =
                EnumSet.noneOf(InstanceDataMigration.Category.class);
        for (Map.Entry<InstanceDataMigration.Category, JFXCheckBox> entry : checks.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        return selected;
    }

    /// Executes the copy on the IO scheduler and reports the outcome.
    private void runMigration(MindustryVersion target,
                              XenonGameRepository repository,
                              @Nullable MindustryVersion source,
                              EnumSet<InstanceDataMigration.Category> categories,
                              boolean overwrite,
                              JFXButton run, Label status) {
        if (source == null) {
            return;
        }
        if (categories.isEmpty()) {
            status.setText(i18n("xenon.mindustry.migrate.select"));
            return;
        }
        Path targetRoot = repository.getVersionRoot(target);
        Path sourceRoot = repository.getVersionRoot(source);
        Path targetData = target.resolveDataDir(targetRoot);
        Path sourceData = source.resolveDataDir(sourceRoot);

        InstanceDataMigration.Request request = new InstanceDataMigration.Request(
                sourceRoot, sourceData, targetRoot, targetData, categories, overwrite);
        run.setDisable(true);
        status.setText(i18n("message.doing"));
        Schedulers.io().execute(() -> {
            try {
                InstanceDataMigration.Result result = InstanceDataMigration.run(request, null);
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.mindustry.migrate.done",
                            result.totalCopied(), result.totalSkipped()));
                    fireEvent(new DialogCloseEvent());
                });
            } catch (IOException ex) {
                LOG.warning("Instance data migration failed for " + target.getId(), ex);
                Platform.runLater(() -> {
                    status.setText(i18n("xenon.mindustry.migrate.failed", ex.getMessage()));
                    run.setDisable(false);
                });
            }
        });
    }
}
