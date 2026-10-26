package com.zlight.sendtosmb.data

import java.io.IOException

/** Persisted identity of a download source. Hashes refer to every byte, not just its name or size. */
data class ResumeRemoteFile(val size: Long, val lastModified: Long, val sha256: String)

/** A retry must retain its partial data and ask the user to choose a new task/source. */
class ResumeConflictException(message: String) : IOException(message)
