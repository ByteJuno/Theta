package io.github.bytejuno.thetacore.agent.ledger

// SQLite affinity is not a type constraint. Bound the projected value in SQL, BEFORE
// Android fills CursorWindow; a getLong/getText check afterwards is too late. These
// expressions receive only source-defined column names, never external SQL input.
// -1 is invalid for every stored revision, size and ordinal. Preserve actual INTEGER
// values (including signed extremes) so the caller's range/binding checks still apply.
internal fun integerColumn(column: String): String =
    "CASE WHEN typeof($column)='integer' THEN $column ELSE -1 END"

// Byte length avoids TEXT character-count expansion and embedded-NUL truncation.
// NULL is an explicit invalid sentinel, never a truncated valid identity/hash.
internal fun textColumn(column: String, maxBytes: Int): String =
    "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB))<=$maxBytes THEN $column ELSE NULL END"

// Read one extra byte to reject oversize, without materializing the damaged value.
// Reject TEXT even if getBlob would coerce it to the expected bytes.
internal fun blobColumn(column: String, maxBytes: Int): String =
    "CASE WHEN typeof($column)='blob' THEN substr($column,1,${maxBytes + 1}) ELSE NULL END"
