package dev.javabyte.shell;

import dev.javabyte.model.GuestInstance;
import dev.javabyte.model.InstanceState;

/** Implemented by generated shell classes; adds the one-time binding of guest state. */
public interface ShellInstance extends GuestInstance {

    /** Attaches the guest state once the JDK superclass constructor has returned. */
    void javabyte$bind(InstanceState state);
}
