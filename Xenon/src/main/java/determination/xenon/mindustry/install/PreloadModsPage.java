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
package determination.xenon.mindustry.install;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.Metadata;
import determination.xenon.mindustry.mod.MindustryModsIndexRepository;
import determination.xenon.mindustry.mod.MindustryRemoteMod;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.RipplerContainer;
import determination.xenon.ui.construct.TwoLineListItem;
import determination.xenon.ui.wizard.WizardController;
import determination.xenon.ui.wizard.WizardPage;
import determination.xenon.util.SettingsMap;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Step 4 of the Xenon install wizard: pre-install community mods into
 * the freshly-isolated instance.
 *
 * <p>HMCL-style card list — each row is a {@link TwoLineListItem} +
 * {@link JFXCheckBox}, scrollable inside a {@link JFXListView}, plus a
 * search field that filters by name / author / repo / description.
 * Cache-first loading: the on-disk {@code mods/community-index.json}
 * cache (if any) populates the list synchronously, then a background
 * task refreshes from upstream and swaps the contents in-place.</p>
 */
public final class PreloadModsPage extends VBox implements WizardPage {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    static final SettingsMap.Key<List<MindustryRemoteMod>> SELECTED_MODS =
            new SettingsMap.Key<>("xenon.preload_mods");

    private final WizardController controller;
    private final XenonInstallWizardProvider.ModIndexLoader loader;
    private final MindustryModsIndexRepository repo =
            new MindustryModsIndexRepository(Metadata.getCachesDirectory());

    private final Label status = new Label();
    private final JFXTextField search = new JFXTextField();
    private final JFXListView<MindustryRemoteMod> list = new JFXListView<>();
    private final Set<MindustryRemoteMod> selected = new LinkedHashSet<>();

    private List<MindustryRemoteMod> all = new ArrayList<>();

    PreloadModsPage(WizardController controller, XenonInstallWizardProvider.ModIndexLoader loader) {
        this.controller = controller;
        this.loader = loader;
        setSpacing(10);
        setPadding(new Insets(16));
        setFillWidth(true);

        // ---- header ----
        Label title = new Label(i18n("xenon.install.preload.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.install.preload.hint"));
        hint.setWrapText(true);

        search.setPromptText(i18n("xenon.mindustry.mod.browser.search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, o, n) -> rebuildList());

        Label selectedCount = new Label();
        selectedCount.setStyle("-fx-text-fill: derive(-fx-text-base-color, -20%);");
        // Cheap: we re-render the count every time the user toggles a row.
        // Stored on the cell itself via a closure below.

        getChildren().addAll(title, hint, search, status);

        // ---- list ----
        list.getStyleClass().add("no-padding");
        list.setItems(FXCollections.observableArrayList());
        list.setCellFactory(lv -> new ModCell(selectedCount));
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setPrefHeight(420);
        getChildren().add(list);
        getChildren().add(selectedCount);

        // ---- actions ----
        JFXButton skip = FXUtils.newRaisedButton(i18n("xenon.install.preload.skip"));
        skip.setOnAction(e -> {
            controller.getSettings().put(SELECTED_MODS, List.of());
            controller.onFinish();
        });
        JFXButton apply = FXUtils.newRaisedButton(i18n("xenon.install.preload.apply"));
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            controller.getSettings().put(SELECTED_MODS, new ArrayList<>(selected));
            controller.onFinish();
        });
        HBox actions = new HBox(8, skip, apply);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(actions);

        // Cache-first paint so the page never opens empty.
        List<MindustryRemoteMod> cached = repo.loadCache();
        if (!cached.isEmpty()) {
            populate(cached);
            status.setText(i18n("xenon.mindustry.mod.browser.cache.loaded", cached.size()));
        } else {
            status.setText(i18n("xenon.install.preload.loading"));
        }
        updateSelectedCount(selectedCount);
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Always kick off a refresh; cache-first painting handled the
        // initial display, but we want fresh data if the network's up.
        Schedulers.io().execute(() -> {
            try {
                List<MindustryRemoteMod> mods = loader.load();
                Platform.runLater(() -> {
                    populate(mods);
                    status.setText(i18n("xenon.install.preload.count", mods.size()));
                });
            } catch (Throwable ex) {
                LOG.warning("Failed to load community mod index", ex);
                Platform.runLater(() -> {
                    if (!all.isEmpty()) {
                        status.setText(i18n("xenon.mindustry.mod.browser.refresh.failed",
                                all.size(), ex.getMessage()));
                    } else {
                        status.setText(i18n("xenon.install.preload.failed")
                                + " " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void populate(List<MindustryRemoteMod> mods) {
        all = new ArrayList<>(mods);
        all.sort((a, b) -> Integer.compare(b.getStars(), a.getStars()));
        rebuildList();
    }

    private void rebuildList() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<MindustryRemoteMod> visible = new ArrayList<>();
        for (MindustryRemoteMod mod : all) {
            if (!matches(mod, q)) continue;
            visible.add(mod);
            if (visible.size() >= 200) break;
        }
        list.getItems().setAll(visible);
    }

    private void updateSelectedCount(Label label) {
        label.setText(i18n("xenon.install.preload.selected", selected.size()));
    }

    private static boolean matches(MindustryRemoteMod mod, String q) {
        if (q.isEmpty()) return true;
        if (contains(mod.displayName(), q)) return true;
        if (contains(mod.getRepo(), q)) return true;
        if (contains(mod.getAuthor(), q)) return true;
        if (contains(mod.getDescription(), q)) return true;
        return false;
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    @Override
    public String getTitle() {
        return i18n("xenon.install.preload.title");
    }

    // ------------------------------------------------------------------
    // HMCL-style card cell with a JFXCheckBox + repo shortcut
    // ------------------------------------------------------------------

    private final class ModCell extends ListCell<MindustryRemoteMod> {
        private final TwoLineListItem twoLine = new TwoLineListItem();
        private final StackPane root = new StackPane();
        private final JFXCheckBox checkBox = new JFXCheckBox();
        private final JFXButton openRepo = FXUtils.newToggleButton4(SVG.GLOBE_BOOK);
        private final Label countLabel;

        ModCell(Label countLabel) {
            this.countLabel = countLabel;

            HBox hbox = new HBox(12);
            hbox.setAlignment(Pos.CENTER_LEFT);

            checkBox.setOnAction(e -> {
                MindustryRemoteMod m = getItem();
                if (m == null) return;
                if (checkBox.isSelected()) selected.add(m); else selected.remove(m);
                updateSelectedCount(countLabel);
            });

            FXUtils.installFastTooltip(openRepo, i18n("xenon.mindustry.mod.browser.repo"));
            openRepo.setOnAction(e -> {
                MindustryRemoteMod m = getItem();
                if (m != null && m.getRepo() != null && !m.getRepo().isBlank()) {
                    FXUtils.openLink("https://github.com/" + m.getRepo());
                }
            });

            HBox.setHgrow(twoLine, Priority.ALWAYS);
            HBox actions = new HBox(8, openRepo);
            actions.setAlignment(Pos.CENTER_RIGHT);

            hbox.getChildren().setAll(checkBox, twoLine, actions);

            root.getStyleClass().add("md-list-cell");
            StackPane.setMargin(hbox, new Insets(10, 16, 10, 16));
            root.getChildren().setAll(new RipplerContainer(hbox));
            // Click anywhere on the row to toggle selection (HMCL behaviour).
            root.setOnMouseClicked(e -> {
                MindustryRemoteMod m = getItem();
                if (m == null) return;
                checkBox.setSelected(!checkBox.isSelected());
                if (checkBox.isSelected()) selected.add(m); else selected.remove(m);
                updateSelectedCount(countLabel);
            });
        }

        @Override
        protected void updateItem(MindustryRemoteMod mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) {
                setGraphic(null);
                return;
            }
            setGraphic(root);

            checkBox.setSelected(selected.contains(mod));

            twoLine.setTitle(mod.displayName());

            StringBuilder subtitle = new StringBuilder();
            if (mod.getRepo() != null && !mod.getRepo().isBlank()) {
                subtitle.append(mod.getRepo());
            }
            if (mod.getAuthor() != null && !mod.getAuthor().isBlank()) {
                if (subtitle.length() > 0) subtitle.append("  ·  ");
                subtitle.append("@").append(mod.getAuthor());
            }
            if (mod.getLastUpdated() != null) {
                if (subtitle.length() > 0) subtitle.append("  ·  ");
                subtitle.append(DATE.format(mod.getLastUpdated()));
            }
            twoLine.setSubtitle(subtitle.length() == 0 ? null : subtitle.toString());

            twoLine.getTags().clear();
            twoLine.addTag("★ " + mod.getStars());
            String desc = mod.getDescription();
            if (desc != null && !desc.isBlank()) {
                String oneLine = desc.replaceAll("\\s+", " ").trim();
                if (oneLine.length() > 80) oneLine = oneLine.substring(0, 80) + "…";
                twoLine.addTag(oneLine);
            }
        }
    }
}
