package campusnet.common;

/**
 * All protocol error codes. Wire format: "ERR CODE_NAME" (e.g. "ERR AUTH_REQUIRED").
 * This enum is in campusnet.common, not campusnet.api, because only server and
 * framing code generate these strings. Clients parse them as plain strings.
 */
public enum ErrorCode {
    AUTH_REQUIRED,
    AUTH_FAILED,
    AUTH_TIMEOUT,
    ALREADY_LOGGED_IN,
    ACCESS_DENIED,
    NO_SUCH_USER,
    BAD_REQUEST,
    UNKNOWN_COMMAND,
    FRAME_TOO_LARGE,
    FILE_TOO_LARGE,
    SLOW_CONSUMER,
    IDLE_TIMEOUT,
    SERVER_BUSY,
    NOT_IMPLEMENTED,
    INTERNAL,
    BAD_TOKEN,
    NOT_FOUND,
    SIZE_MISMATCH,
    TIMEOUT;

    /** Returns the wire-format error string, e.g. "ERR AUTH_REQUIRED". */
    public String toWire() {
        return "ERR " + name();
    }
}
