package io.github.revenge.plugins

/**
 * A version under the Revenge versioning scheme.
 *
 * ## Format
 *
 * `<n>[.<n>]*(-<label>)?`
 *
 * One or more non-negative integer segments, optionally followed by a single lowercase-alphanumeric label.
 * Leading zeros in numeric segments are allowed and insignificant (`1.02` == `1.2`).
 *
 * ### Ordering
 *
 * 1. Number are split into segments by `.`; the shorter side right-padded with zeros (`1.2` == `1.2.0`).
 * 2. If numeric parts are equal: bare > labeled (`1.2.0` > `1.2.0-rc`). Every label is a prerelease.
 * 3. If both labeled, digit-run comparison is done. Labels are split into alternating non-digit/digit runs;
 *    non-digit runs compare byte-lexically, digit runs numerically (`beta2` < `beta10`).
 *    A label that is a run-prefix of another sorts first (`beta` < `beta1`).
 */
data class Version(
    val nums: List<Int>,
    val label: String? = null,
) : Comparable<Version> {
    init {
        require(nums.isNotEmpty()) { "Version must have at least one numeric segment" }
        require(nums.all { it >= 0 }) { "Version segments must be non-negative" }
        label?.let { require(LABEL_REGEX.matches(it)) { "Invalid version label: $it" } }
    }

    override fun compareTo(other: Version): Int {
        val length = maxOf(nums.size, other.nums.size)
        for (i in 0 until length) {
            val a = nums.getOrElse(i) { 0 }
            val b = other.nums.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }

        // Numeric parts equal: bare > labeled.
        if (label == null && other.label == null) return 0
        if (label == null) return 1
        if (other.label == null) return -1

        return compareLabels(label, other.label)
    }

    override fun toString(): String = nums.joinToString(".") + (label?.let { "-$it" } ?: "")

    companion object {
        private val LABEL_REGEX = Regex("[a-z0-9]+")

        /**
         * Parses a version string. 
         * Leading zeros in numeric segments are allowed and insignificant (`1.02` == `1.2`).
         *
         * Throws [IllegalArgumentException] on: empty string, non-numeric segments, empty label (`1.0-`),
         * or labels containing anything but `[a-z0-9]`.
         */
        fun parse(value: String): Version {
            require(value.isNotEmpty()) { "Version string must not be empty" }
            val dash = value.indexOf('-')
            val numsPart = if (dash >= 0) value.substring(0, dash) else value
            val label = if (dash >= 0) value.substring(dash + 1) else null

            val nums = numsPart.split('.').map {
                it.toIntOrNull()?.takeIf { n -> n >= 0 }
                    ?: throw IllegalArgumentException("Invalid version numeric segment: '$it' in '$value'")
            }
            if (label != null) require(LABEL_REGEX.matches(label)) { "Invalid version label: '$label' in '$value'" }

            return Version(nums, label)
        }

        /**
         * Digit-run comparison: split into alternating runs of non-digits and digits;
         * non-digit runs compare byte-lexically, digit runs numerically.
         */
        private fun compareLabels(a: String, b: String): Int {
            val runsA = splitRuns(a)
            val runsB = splitRuns(b)
            val length = minOf(runsA.size, runsB.size)
            for (i in 0 until length) {
                val ra = runsA[i]
                val rb = runsB[i]
                val bothDigits = ra[0].isDigit() && rb[0].isDigit()
                val cmp = if (bothDigits) {
                    ra.trimStart('0').padStart(1, '0').let { na ->
                        rb.trimStart('0').padStart(1, '0').let { nb ->
                            if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
                        }
                    }
                } else {
                    ra.compareTo(rb)
                }
                if (cmp != 0) return cmp
            }
            // Prefix run sequence sorts first (`beta` < `beta1`).
            return runsA.size.compareTo(runsB.size)
        }

        private fun splitRuns(label: String): List<String> {
            val runs = mutableListOf<String>()
            var start = 0
            for (i in 1..label.length) {
                if (i == label.length || label[i].isDigit() != label[start].isDigit()) {
                    runs += label.substring(start, i)
                    start = i
                }
            }
            return runs
        }
    }
}

/**
 * A version range: either the wildcard `"*"` (satisfied by every version) or a conjunction of
 * explicit bounds (`<` `<=` `=` `>=` `>`), e.g. `">=1.0 <2"`.
 *
 * Satisfaction is evaluated on the **integers only**, the candidate's label is stripped before
 * bounds checking (`1.5-rc` satisfies `>=1.0 <2`; `2.0-rc` does NOT satisfy `<2`).
 * 
 * Bounds themselves must be bare versions (no labels).
 */
class VersionRange private constructor(val bounds: List<Bound>) {
    class Bound(val op: Op, val version: Version)

    enum class Op(val symbol: String) {
        LTE("<="), GTE(">="), LT("<"), GT(">"), EQ("=");
    }

    /** Whether [version] satisfies every bound, compared on integers only (label stripped). */
    fun satisfies(version: Version): Boolean {
        val bare = if (version.label == null) version else Version(version.nums)
        return bounds.all { bound ->
            val cmp = bare.compareTo(bound.version)
            when (bound.op) {
                Op.LT -> cmp < 0
                Op.LTE -> cmp <= 0
                Op.EQ -> cmp == 0
                Op.GTE -> cmp >= 0
                Op.GT -> cmp > 0
            }
        }
    }

    override fun toString(): String =
        if (bounds.isEmpty()) "*" else bounds.joinToString(" ") { "${it.op.symbol}${it.version}" }

    companion object {
        /** The wildcard range (`"*"`): satisfied by every version, labels included. */
        val ANY = VersionRange(emptyList())

        /**
         * Parses `"*"` to [ANY], or a whitespace-separated conjunction of bounds: `">=1.0 <2"`.
         *
         * Throws [IllegalArgumentException] on blank input, unknown operators (`^`, `~`),
         * missing operator, or labeled bound versions (`>=1.0-rc`).
         */
        fun parse(value: String): VersionRange {
            if (value.trim() == "*") return ANY

            val tokens = value.trim().split(Regex("\\s+"))
            require(tokens.isNotEmpty() && tokens.first().isNotEmpty()) { "Version range must not be empty" }

            val bounds = tokens.map { token ->
                // Order matters: two-char operators before their one-char prefixes.
                val op = Op.entries.firstOrNull { token.startsWith(it.symbol) }
                    ?: throw IllegalArgumentException("Invalid version range bound: '$token' in '$value'")
                val version = Version.parse(token.removePrefix(op.symbol))
                require(version.label == null) { "Version range bounds must not be labeled: '$token' in '$value'" }
                Bound(op, version)
            }
            return VersionRange(bounds)
        }
    }
}
