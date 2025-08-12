package org.knifefish.dependency.helper.util

object VersionComparator {

    private val prereleaseMarkers = listOf("alpha", "beta", "rc", "m", "milestone", "snapshot", "preview", "dev")

    fun isStable(version: String): Boolean {
        val lower = version.lowercase()
        return prereleaseMarkers.none { lower.contains(it) }
    }

    fun compare(left: String, right: String): Int {
        if (left == right) {
            return 0
        }

        val leftTokens = tokenize(left)
        val rightTokens = tokenize(right)
        val maxSize = maxOf(leftTokens.size, rightTokens.size)
        for (index in 0 until maxSize) {
            val leftToken = leftTokens.getOrNull(index)
            val rightToken = rightTokens.getOrNull(index)
            if (leftToken == rightToken) {
                continue
            }
            if (leftToken == null) {
                return -1
            }
            if (rightToken == null) {
                return 1
            }
            val result = compareToken(leftToken, rightToken)
            if (result != 0) {
                return result
            }
        }
        return left.compareTo(right)
    }

    fun newestStable(versions: List<String>): String? =
        versions.filter { isStable(it) }.maxWithOrNull(::compare)

    private fun tokenize(version: String): List<String> =
        version.split('.', '-', '_').flatMap { token ->
            token.split(Regex("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)"))
        }.filter { it.isNotBlank() }

    private fun compareToken(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> 1
            rightNumber != null -> -1
            else -> left.compareTo(right)
        }
    }
}
