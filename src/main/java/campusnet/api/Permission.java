package campusnet.api;

/**
 * Permissions checked by the Dispatcher before calling a handler.
 * NONE means authentication alone is sufficient (no specific permission needed).
 * FROZEN — do not change without following the contract change procedure.
 */
public enum Permission {
    NONE,
    CHAT_BROADCAST,
    CHAT_PRIVATE,
    FILE_UPLOAD,
    FILE_DOWNLOAD
}
