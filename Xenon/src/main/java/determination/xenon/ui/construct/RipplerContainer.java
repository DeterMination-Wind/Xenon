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
package determination.xenon.ui.construct;

import com.jfoenix.controls.JFXRippler;
import javafx.animation.Animation;
import javafx.animation.Transition;
import javafx.css.*;
import javafx.css.converter.PaintConverter;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import determination.xenon.theme.Themes;
import determination.xenon.ui.animation.AnimationUtils;
import determination.xenon.ui.animation.Motion;

import java.util.ArrayList;
import java.util.List;

public class RipplerContainer extends StackPane {
    private static final String DEFAULT_STYLE_CLASS = "rippler-container";
    private static final CornerRadii DEFAULT_RADII = new CornerRadii(3);
    private static final Color DEFAULT_RIPPLER_FILL = Color.rgb(0, 200, 255);

    private final Node container;

    private final StackPane buttonContainer = new StackPane();
    private final JFXRippler buttonRippler = new JFXRippler(new StackPane()) {
        private static final Background DEFAULT_MASK_BACKGROUND = new Background(new BackgroundFill(Color.WHITE, DEFAULT_RADII, Insets.EMPTY));

        @Override
        protected Node getMask() {
            StackPane mask = new StackPane();
            mask.shapeProperty().bind(buttonContainer.shapeProperty());
            mask.setBackground(DEFAULT_MASK_BACKGROUND);
            mask.resize(
                    buttonContainer.getWidth() - buttonContainer.snappedRightInset() - buttonContainer.snappedLeftInset(),
                    buttonContainer.getHeight() - buttonContainer.snappedBottomInset() - buttonContainer.snappedTopInset()
            );
            return mask;
        }
    };

    private Transition coverAnimation;
    /**
     * Drives the hover-tint alpha (0 = transparent, 1 = full tint). Kept
     * around so consecutive enter/exit events animate from the *current*
     * alpha instead of jumping back to 0 — the previous code created a
     * brand-new Transition per event, which on rapid mouse traversal
     * forced the background to null and back, snapping the title text
     * across snap-to-pixel boundaries (the "hover shake").
     */
    private final javafx.beans.property.DoubleProperty hoverAlpha =
            new javafx.beans.property.SimpleDoubleProperty(0.0);

    public RipplerContainer(Node container) {
        this.container = container;

        getStyleClass().add(DEFAULT_STYLE_CLASS);
        buttonRippler.setPosition(JFXRippler.RipplerPos.BACK);
        buttonContainer.getChildren().add(buttonRippler);
        focusedProperty().addListener((a, b, newValue) -> {
            if (newValue) {
                if (!isPressed())
                    buttonRippler.showOverlay();
            } else {
                buttonRippler.hideOverlay();
            }
        });
        pressedProperty().addListener(o -> buttonRippler.hideOverlay());
        setPickOnBounds(false);

        buttonContainer.setPickOnBounds(false);

        updateChildren();

        var shape = new Rectangle();
        shape.widthProperty().bind(widthProperty());
        shape.heightProperty().bind(heightProperty());
        // Detach the shape from the layout pass — Region's layout already
        // resolves its own bounds; leaving the rectangle managed creates a
        // shape↔region geom-changed feedback loop that re-snaps text on
        // every hover transition.
        shape.setManaged(false);
        setShape(shape);

        // Background is always present; only its alpha changes. This kills
        // the null↔non-null layout invalidation that was shaking the text.
        hoverAlpha.addListener((obs, o, n) -> applyHoverAlpha(n.doubleValue()));
        applyHoverAlpha(0.0);

        EventHandler<MouseEvent> mouseEventHandler;
        if (AnimationUtils.isAnimationEnabled()) {
            mouseEventHandler = event -> {
                double target = event.getEventType() == MouseEvent.MOUSE_ENTERED ? 1.0 : 0.0;
                if (coverAnimation != null && coverAnimation.getStatus() == Animation.Status.RUNNING) {
                    coverAnimation.stop();
                }
                final double from = hoverAlpha.get();
                coverAnimation = new Transition() {
                    {
                        setCycleDuration(Motion.SHORT4);
                        setInterpolator(target > from ? Motion.EASE_IN : Motion.EASE_OUT);
                    }

                    @Override
                    protected void interpolate(double frac) {
                        hoverAlpha.set(from + (target - from) * frac);
                    }
                };
                coverAnimation.play();
            };
        } else {
            mouseEventHandler = event ->
                    hoverAlpha.set(event.getEventType() == MouseEvent.MOUSE_ENTERED ? 1.0 : 0.0);
        }

        addEventHandler(MouseEvent.MOUSE_ENTERED, mouseEventHandler);
        addEventHandler(MouseEvent.MOUSE_EXITED, mouseEventHandler);
    }

    private void applyHoverAlpha(double frac) {
        if (frac <= 0.0) {
            // Use a transparent fill rather than a null Background — null
            // changes the Region's "filled" flag and triggers a layout
            // pass, which is what was jiggling the title text.
            setBackground(new Background(new BackgroundFill(
                    Color.TRANSPARENT, CornerRadii.EMPTY, Insets.EMPTY)));
            return;
        }
        Color onSurface = Themes.getColorScheme().getOnSurface();
        setBackground(new Background(new BackgroundFill(
                Color.color(onSurface.getRed(), onSurface.getGreen(), onSurface.getBlue(),
                        frac * 0.04),
                CornerRadii.EMPTY, Insets.EMPTY)));
    }

    protected void updateChildren() {
        Node container = getContainer();
        if (buttonRippler.getPosition() == JFXRippler.RipplerPos.BACK) {
            getChildren().setAll(buttonContainer, container);
            container.setPickOnBounds(false);
        } else {
            getChildren().setAll(container, buttonContainer);
            buttonContainer.setPickOnBounds(false);
        }
    }

    public void setPosition(JFXRippler.RipplerPos pos) {
        buttonRippler.setPosition(pos);
        updateChildren();
    }

    public JFXRippler getRippler() {
        return buttonRippler;
    }

    public Node getContainer() {
        return container;
    }

    private final StyleableObjectProperty<Paint> ripplerFill = new StyleableObjectProperty<>(DEFAULT_RIPPLER_FILL) {
        @Override
        public Object getBean() {
            return RipplerContainer.this;
        }

        @Override
        public String getName() {
            return "ripplerFill";
        }

        @Override
        public CssMetaData<? extends Styleable, Paint> getCssMetaData() {
            return StyleableProperties.RIPPLER_FILL;
        }

        @Override
        protected void invalidated() {
            buttonRippler.setRipplerFill(get());
        }
    };

    public StyleableObjectProperty<Paint> ripplerFillProperty() {
        return ripplerFill;
    }

    public Paint getRipplerFill() {
        return ripplerFillProperty().get();
    }

    public void setRipplerFill(Paint ripplerFill) {
        ripplerFillProperty().set(ripplerFill);
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return getClassCssMetaData();
    }

    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.STYLEABLES;
    }

    private final static class StyleableProperties {
        private static final CssMetaData<RipplerContainer, Paint> RIPPLER_FILL = new CssMetaData<>("-jfx-rippler-fill", PaintConverter.getInstance(), DEFAULT_RIPPLER_FILL) {
            @Override
            public boolean isSettable(RipplerContainer styleable) {
                return styleable.ripplerFill == null || !styleable.ripplerFill.isBound();
            }

            @Override
            public StyleableProperty<Paint> getStyleableProperty(RipplerContainer styleable) {
                return styleable.ripplerFillProperty();
            }
        };

        private static final List<CssMetaData<? extends Styleable, ?>> STYLEABLES;

        static {
            var styleables = new ArrayList<>(StackPane.getClassCssMetaData());
            styleables.add(RIPPLER_FILL);
            STYLEABLES = List.copyOf(styleables);
        }
    }
}
