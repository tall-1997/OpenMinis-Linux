package com.openminis.app.data

/**
 * Thrown when a bounded read cannot obtain its [com.openminis.app.data.body.Admission]
 * reservation. Distinct from "no matches" so callers can retry or report a
 * temporary failure instead of treating refusal as an empty result set.
 */
class SearchAdmissionUnavailable(message: String) : IllegalStateException(message)
