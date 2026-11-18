package campusnet.api;

/**
 * The data embedded in a redeemed file-transfer token.
 * size is 0 for GET operations (the actual size is learned from the stored file).
 * FROZEN — do not change without following the contract change procedure.
 */
public record Grant(Principal who, Op op, String fileName, long size) {}
