package campusnet.api;

/**
 * An authenticated identity: username and role.
 * Immutable value object (Java record).
 * FROZEN — do not change without following the contract change procedure.
 */
public record Principal(String user, Role role) {}
