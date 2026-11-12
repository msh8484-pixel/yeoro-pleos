package ai.yeoro.service

private const val BUFFER_KEEP_MS = 24 * 3_600_000L

/** 버퍼 정리 기준 시각: 24시간보다 오래된 순간을 지우되, 살아 있는 후보의 출발 이후 순간은 남긴다. */
fun purgeCutoff(nowMs: Long, candidateSinceMs: Long?): Long = minOf(nowMs - BUFFER_KEEP_MS, candidateSinceMs ?: Long.MAX_VALUE)
