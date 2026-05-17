/*
 * Xenon Launcher
 * Copyright (C) 2025-2026  Xenon contributors
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
package determination.xenon.ui.nbt;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Skin;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.TreeViewSkin;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.glavo.nbt.NBTElement;
import org.glavo.nbt.tag.Tag;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PageCloseEvent;
import determination.xenon.ui.construct.SpinnerPane;
import determination.xenon.ui.decorator.DecoratorPage;
import determination.xenon.util.StringUtils;
import determination.xenon.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Path;

import static determination.xenon.ui.FXUtils.onEscPressed;
import static determination.xenon.util.logging.Logger.LOG;
import static determination.xenon.util.i18n.I18n.i18n;

/**
 * @author Glavo
 */
public final class NBTEditorPage extends SpinnerPane implements DecoratorPage {
    private final ReadOnlyObjectWrapper<State> state;
    private final Path file;
    private final NBTFileType type;

    private final BorderPane root = new BorderPane();

    public NBTEditorPage(Path file) throws IOException {
        getStyleClass().add("gray-background");

        this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("nbt.title", file.toString())));
        this.file = file;

        //noinspection DataFlowIssue
        this.type = NBTFileType.ofFile(file);
        if (type == null) {
            throw new IOException("Unknown type of file " + file);
        }

        setContent(root);
        setLoading(true);

        HBox actions = new HBox(8);
        actions.setPadding(new Insets(8));
        actions.setAlignment(Pos.CENTER_RIGHT);

        JFXButton saveButton = FXUtils.newRaisedButton(i18n("button.save"));
        saveButton.setOnAction(e -> {
            try {
                save();
            } catch (IOException ex) {
                LOG.warning("Failed to save NBT file", ex);
                Controllers.dialog(i18n("nbt.save.failed") + "\n\n" + StringUtils.getStackTrace(ex));
            }
        });

        JFXButton cancelButton = FXUtils.newRaisedButton(i18n("button.cancel"));
        cancelButton.setOnAction(e -> fireEvent(new PageCloseEvent()));
        onEscPressed(this, cancelButton::fire);

        actions.getChildren().setAll(saveButton, cancelButton);

        Task.supplyAsync(() -> type.read(file))
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    if (exception == null) {
                        setLoading(false);

                        NBTTreeItem root = new NBTTreeItem(result, FileUtils.getName(file));
                        var view = new TreeView<>(root) {
                            @Override
                            protected Skin<?> createDefaultSkin() {
                                return new TreeViewSkin<>(this) {
                                    {
                                        FXUtils.smoothScrolling(getVirtualFlow());
                                    }
                                };
                            }
                        };
                        view.setCellFactory(ignored -> new NBTTreeCell());
                        view.addEventHandler(TreeItem.<NBTElement>branchExpandedEvent(), event -> {
                            TreeItem<NBTElement> item = event.getTreeItem();
                            if (item.getValue() instanceof Tag && item.getChildren().size() == 1)
                                item.getChildren().get(0).setExpanded(true);
                        });
                        root.setExpanded(true);

                        BorderPane.setMargin(view, new Insets(10));
                        onEscPressed(view, cancelButton::fire);
                        this.root.setCenter(view);
                    } else {
                        LOG.warning("Fail to open nbt file", exception);
                        Controllers.dialog(i18n("nbt.open.failed") + "\n\n" + StringUtils.getStackTrace(exception), null, MessageDialogPane.MessageType.WARNING, cancelButton::fire);
                    }
                }).start();
    }

    public void save() throws IOException {
        // TODO
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state;
    }
}
