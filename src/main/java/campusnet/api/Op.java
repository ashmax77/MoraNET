package campusnet.api;

/**
 * File operation type, embedded in a token Grant.
 * PUT = upload, GET = download.
 * FROZEN — do not change without following the contract change procedure.
 */
public enum Op {
    PUT,
    GET
}
