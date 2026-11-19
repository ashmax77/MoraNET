package campusnet.api;

/**
 * User roles. STUDENT and LECTURER are intranet users with full access.
 * EXTERNAL is an extranet user with restricted access (no PRIVATE, no file ops).
 * FROZEN — do not change without following the contract change procedure.
 */
public enum Role {
    STUDENT,
    LECTURER,
    EXTERNAL
}
