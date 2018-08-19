package org.sufficientlysecure.rootcommands.util;

import org.sufficientlysecure.rootcommands.command.Command;
import org.sufficientlysecure.rootcommands.command.SimpleCommand;

public class FailedExecuteCommand extends Exception {

    private Command command;

    public FailedExecuteCommand(Command command) {
        super("Failed execute " + command.getCommand());
        this.command = command;
    }

    public SimpleCommand getCommand() {
        return (SimpleCommand) command;
    }
    public String toString() {
        return "Failed execute: " + command.getCommand() + "////" + ((SimpleCommand)command).getOutput();
    }
}
