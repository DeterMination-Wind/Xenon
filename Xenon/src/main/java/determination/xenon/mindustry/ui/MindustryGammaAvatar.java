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

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Draws a stylised Mindustry "Gamma" core-machine sprite onto a
 * {@link Canvas}. Used as the default avatar for Xenon offline accounts
 * (the user's UID + nickname identity) instead of the Minecraft-style
 * Steve face HMCL ships with.
 *
 * <p>The drawing is procedural — no PNG dependency — so it scales to
 * whatever size the avatar canvas has without needing @1x / @2x assets.
 * The shapes are a simplified read of the in-game Gamma silhouette:
 * an octagonal body, a cyan visor, and short mecha shoulders.</p>
 */
public final class MindustryGammaAvatar {

    /** Mindustry's accent palette — keeps the avatar readable on dark + light themes. */
    private static final Color BODY_FILL = Color.web("#f5b73d");
    private static final Color BODY_STROKE = Color.web("#a17a1a");
    private static final Color VISOR = Color.web("#5fd1ff");
    private static final Color VISOR_STROKE = Color.web("#1d4a66");
    private static final Color SHOULDER = Color.web("#e08c2a");

    private MindustryGammaAvatar() {
    }

    public static void draw(Canvas canvas) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);

        double cx = w / 2.0;
        double cy = h / 2.0;
        double r = Math.min(w, h) / 2.0 - 1;

        g.setImageSmoothing(true);

        // Shoulders — two short slabs flanking the body.
        g.setFill(SHOULDER);
        double sw = r * 0.55;
        double sh = r * 0.34;
        g.fillRoundRect(cx - r, cy - sh / 2.0, sw, sh, 4, 4);
        g.fillRoundRect(cx + r - sw, cy - sh / 2.0, sw, sh, 4, 4);

        // Octagonal body.
        double[] xs = new double[8];
        double[] ys = new double[8];
        double bodyR = r * 0.78;
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(22.5 + 45.0 * i);
            xs[i] = cx + Math.cos(angle) * bodyR;
            ys[i] = cy + Math.sin(angle) * bodyR;
        }
        g.setFill(BODY_FILL);
        g.fillPolygon(xs, ys, 8);
        g.setStroke(BODY_STROKE);
        g.setLineWidth(Math.max(1.0, r * 0.06));
        g.strokePolygon(xs, ys, 8);

        // Visor — horizontal cyan band, slightly above centre, classic Mindustry "eye".
        double vw = r * 1.05;
        double vh = r * 0.32;
        g.setFill(VISOR);
        g.fillRoundRect(cx - vw / 2.0, cy - vh * 0.25, vw, vh, vh, vh);
        g.setStroke(VISOR_STROKE);
        g.setLineWidth(Math.max(1.0, r * 0.04));
        g.strokeRoundRect(cx - vw / 2.0, cy - vh * 0.25, vw, vh, vh, vh);

        // Visor highlight — single bright pip on the left, suggests "powered on".
        g.setFill(Color.WHITE);
        double pip = vh * 0.32;
        g.fillOval(cx - vw / 2.0 + pip, cy - vh * 0.25 + pip * 0.6, pip, pip);
    }
}
