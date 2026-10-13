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

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.MindustryDownloadSource;
import determination.xenon.ui.wizard.WizardController;
import determination.xenon.ui.wizard.WizardPage;
import determination.xenon.util.SettingsMap;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;

/**
 * Step 1 of the Xenon install wizard: choose a Mindustry variant.
 *
 * <p>One {@link Button} per variant; clicking advances to the version
 * list for that variant. The custom variant short-circuits to "drop a
 * jar through the import flow instead", so we do not present a list.</p>
 */
public final class VariantSelectionPage extends VBox implements WizardPage {
    private final WizardController controller;

    /// Page heading, kept as a field so [#rebuild()] can re-add it.
    private final Label title = new Label(i18n("xenon.install.variant.title"));

    /// Page hint, kept as a field so [#rebuild()] can re-add it.
    private final Label hint = new Label(i18n("xenon.install.variant.hint"));

    public VariantSelectionPage(WizardController controller) {
        this.controller = controller;
        setSpacing(8);
        setPadding(new Insets(16));

        title.getStyleClass().add("title");
        hint.setWrapText(true);
        rebuild();
    }

    /// Rebuilds the variant cards against the tier currently in effect.
    ///
    /// The download source can change while the launcher is open, so the
    /// availability of each variant is re-evaluated on every navigation. A
    /// variant with no allowed feed is offered as a disabled card with the
    /// reason spelled out rather than silently dropped from the list.
    private void rebuild() {
        MindustryDownloadSource source = MindustryDownloadSource.current();
        getChildren().setAll(title, hint);
        for (VersionVariant variant : VersionVariant.values()) {
            if (variant == VersionVariant.CUSTOM) continue;
            getChildren().add(buildCard(variant, source));
        }
    }

    private Region buildCard(VersionVariant variant, MindustryDownloadSource source) {
        boolean available = source.allowsVariant(variant);

        VBox card = new VBox(4);
        card.setPadding(new Insets(12));
        card.getStyleClass().add("card");

        Label name = new Label(variant.getDisplayName());
        name.getStyleClass().add("card-title");
        Label desc = new Label(i18n("xenon.install.variant." + variant.name().toLowerCase(Locale.ROOT) + ".desc"));
        desc.setWrapText(true);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button choose = new Button(i18n("xenon.install.variant.choose"));
        choose.setDisable(!available);
        choose.setOnAction(e -> {
            controller.getSettings().put(WizardKeys.VARIANT, variant);
            controller.onNext();
        });

        Hyperlink upstream = new Hyperlink(i18n("xenon.install.variant.upstream"));
        String url = variant.getUpstreamUrl();
        if (url != null) {
            upstream.setOnAction(e -> {
                try {
                    Desktop.getDesktop().browse(new URI(url));
                } catch (Exception ignored) {
                    // Best-effort; user can still copy the URL from the wizard hint.
                }
            });
        }

        actions.getChildren().addAll(choose, upstream);
        card.getChildren().addAll(name, desc, actions);

        if (!available) {
            Label reason = new Label(i18n("xenon.install.variant.unavailable",
                    i18n("settings.launcher.mindustry_download.source."
                            + source.name().toLowerCase(Locale.ROOT))));
            reason.setWrapText(true);
            reason.getStyleClass().add("subtitle-label");
            card.getChildren().add(reason);
        }
        return card;
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // No reset required — variant gets overwritten when the user clicks a
        // card. The rebuild is what keeps the cards in step with the tier.
        rebuild();
    }

    @Override
    public String getTitle() {
        return i18n("xenon.install.variant.title");
    }
}
