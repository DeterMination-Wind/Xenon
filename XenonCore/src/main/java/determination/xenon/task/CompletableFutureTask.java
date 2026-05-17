/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon.task;

import determination.xenon.util.Lang;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public abstract class CompletableFutureTask<T> extends Task<T> {

    @Override
    public void execute() throws Exception {
    }

    public abstract CompletableFuture<T> getFuture(TaskCompletableFuture executor);

    public static class CustomException extends RuntimeException {}

    protected static CompletableFuture<Void> breakable(CompletableFuture<?> future) {
        return future.thenApplyAsync(unused1 -> (Void) null).exceptionally(throwable -> {
            if (Lang.resolveException(throwable) instanceof CustomException) return null;
            else throw new CompletionException(throwable);
        });
    }

}
