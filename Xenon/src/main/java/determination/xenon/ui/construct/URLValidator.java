/*
 * Xenon Launcher
 * Copyright (C) 2022-2026  Xenon contributors
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
package determination.xenon.ui.construct;

import com.jfoenix.validation.base.ValidatorBase;
import javafx.beans.NamedArg;
import javafx.scene.control.TextInputControl;
import org.glavo.url.WebURL;
import determination.xenon.util.StringUtils;

import static determination.xenon.util.i18n.I18n.i18n;

public class URLValidator extends ValidatorBase {
    private final boolean nullable;

    public URLValidator() {
        this(false);
    }

    public URLValidator(@NamedArg("nullable") boolean nullable) {
        this(i18n("input.url"), nullable);
    }

    public URLValidator(@NamedArg("message") String message, @NamedArg("nullable") boolean nullable) {
        super(message);
        this.nullable = nullable;
    }

    @Override
    protected void eval() {
        if (srcControl.get() instanceof TextInputControl) {
            evalTextInputField();
        }
    }

    private void evalTextInputField() {
        TextInputControl textField = ((TextInputControl) srcControl.get());

        if (StringUtils.isBlank(textField.getText()))
            hasErrors.set(!nullable);
        else {
            hasErrors.set(WebURL.tryParseBrowserInput(textField.getText()) == null);
        }
    }
}
