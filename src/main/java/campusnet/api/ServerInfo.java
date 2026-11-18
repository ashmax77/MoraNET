package campusnet.api;

/**
 * Network address of a discovered server: hostname, chat port, file port.
 * Returned by ServerLocator.locate().
 * FROZEN — do not change without following the contract change procedure.
 */
public record ServerInfo(String host, int chatPort, int filePort) {}
