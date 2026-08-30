/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.command.exception;

import baritone.api.command.argument.ICommandArgument;

public class CommandInvalidTypeException extends CommandInvalidArgumentException {

    /** Long enough for a list of a few mods, short enough to still read as one chat line */
    private static final int MAX_REASON_LENGTH = 200;

    public CommandInvalidTypeException(ICommandArgument arg, String expected) {
        super(arg, String.format("Expected %s", expected));
    }

    public CommandInvalidTypeException(ICommandArgument arg, String expected, Throwable cause) {
        // Include what actually went wrong. Without this the only thing a player sees is the name of the parser that
        // rejected their argument, which hides messages that were written specifically for them, such as the list of
        // mods providing a block name that more than one mod claims.
        super(arg, String.format("Expected %s%s", expected, reason(cause)), cause);
    }

    /**
     * Only an {@link IllegalArgumentException} is treated as something worth showing: those carry a message written
     * for whoever typed the command. Anything else is a failure inside Baritone, whose message would be noise here
     * and is available through the verboseCommandExceptions setting instead.
     */
    private static String reason(Throwable cause) {
        if (!(cause instanceof IllegalArgumentException) || cause.getMessage() == null) {
            return "";
        }
        String message = cause.getMessage().replace('\u00a7', '&');
        if (message.length() > MAX_REASON_LENGTH) {
            message = message.substring(0, MAX_REASON_LENGTH) + "...";
        }
        return ": " + message;
    }

    public CommandInvalidTypeException(ICommandArgument arg, String expected, String got) {
        super(arg, String.format("Expected %s, but got %s instead", expected, got));
    }

    public CommandInvalidTypeException(ICommandArgument arg, String expected, String got, Throwable cause) {
        super(arg, String.format("Expected %s, but got %s instead", expected, got), cause);
    }
}
