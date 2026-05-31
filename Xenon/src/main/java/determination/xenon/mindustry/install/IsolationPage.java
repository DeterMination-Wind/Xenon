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

import determination.xenon.mindustry.DataDirectoryPolicy;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.ui.wizard.WizardController;
import determination.xenon.ui.wizard.WizardPage;
import determination.xenon.util.SettingsMap;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;

/**
 * Step 3 of the Xenon install wizard: name the new version and choose
 * a {@link DataDirectoryPolicy}. Defaults skew toward isolation —
 * cross-version save corruption is the failure mode users notice last
 * and complain about most.
 */
public final class IsolationPage extends VBox implements WizardPage {
    private final WizardController controller;
    private final TextField idField;
    private final ToggleGroup policyGroup;
    private final RadioButton isolated;
    private final RadioButton global;
    private final RadioButton custom;
    private final TextField customDir;

    public IsolationPage(WizardController controller) {
        this.controller = controller;
        setSpacing(10);
        setPadding(new Insets(16));

        getChildren().add(new Label(i18n("xenon.install.isolation.title")));

        idField = new TextField();
        idField.setPromptText(i18n("xenon.install.isolation.id.prompt"));
        getChildren().addAll(new Label(i18n("xenon.install.isolation.id.label")), idField);

        policyGroup = new ToggleGroup();
        isolated = new RadioButton(i18n("xenon.install.isolation.isolated"));
        isolated.setToggleGroup(policyGroup);
        isolated.setSelected(true);
        global = new RadioButton(i18n("xenon.install.isolation.global"));
        global.setToggleGroup(policyGroup);
        custom = new RadioButton(i18n("xenon.install.isolation.custom"));
        custom.setToggleGroup(policyGroup);

        customDir = new TextField();
        customDir.setPromptText(i18n("xenon.install.isolation.custom.prompt"));
        customDir.disableProperty().bind(custom.selectedProperty().not());

        getChildren().addAll(new Label(i18n("xenon.install.isolation.policy.label")),
                isolated, global, custom, customDir);

        Button next = new Button(i18n("wizard.next"));
        next.setDefaultButton(true);
        next.setOnAction(e -> {
            String id = idField.getText() == null ? "" : idField.getText().trim();
            if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]+")) {
                idField.requestFocus();
                return;
            }
            if (MindustryImportFlow.repository().has(id)
                    && Boolean.FALSE.equals(controller.getSettings().get(WizardKeys.OVERRIDE_EXISTING))) {
                idField.requestFocus();
                return;
            }
            DataDirectoryPolicy policy = isolated.isSelected() ? DataDirectoryPolicy.ISOLATED
                    : global.isSelected() ? DataDirectoryPolicy.GLOBAL
                    : DataDirectoryPolicy.CUSTOM;
            controller.getSettings().put(WizardKeys.VERSION_ID, id);
            controller.getSettings().put(WizardKeys.DATA_DIR_POLICY, policy);
            if (policy == DataDirectoryPolicy.CUSTOM) {
                String dir = customDir.getText() == null ? "" : customDir.getText().trim();
                if (dir.isEmpty()) {
                    customDir.requestFocus();
                    return;
                }
                controller.getSettings().put(WizardKeys.CUSTOM_DATA_DIR, dir);
            }
            controller.onNext();
        });
        HBox bottom = new HBox(8, next);
        getChildren().add(bottom);
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        MindustryRemoteVersion ver = settings.get(WizardKeys.REMOTE_VERSION);
        if (ver != null && (idField.getText() == null || idField.getText().isEmpty())) {
            idField.setText(ver.getVariant().name().toLowerCase(Locale.ROOT) + "-" + ver.getBuild());
        }
    }

    @Override
    public String getTitle() {
        return i18n("xenon.install.isolation.title");
    }
}
