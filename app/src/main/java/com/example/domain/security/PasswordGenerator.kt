package com.example.domain.security

import java.security.SecureRandom

object PasswordGenerator {
    const val UPPER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val LOWER_CHARS = "abcdefghijklmnopqrstuvwxyz"
    const val NUMBER_CHARS = "0123456789"
    const val SYMBOL_CHARS = "!@#$%^&*()_+-=[]{}|;:,.<>?"

    fun generatePassword(
        length: Int,
        upper: Boolean,
        lower: Boolean,
        nums: Boolean,
        syms: Boolean
    ): String {
        val pools = mutableListOf<String>()
        val guaranteed = mutableListOf<Char>()
        val random = SecureRandom()

        if (upper) {
            pools.add(UPPER_CHARS)
            guaranteed.add(UPPER_CHARS[random.nextInt(UPPER_CHARS.length)])
        }
        if (lower) {
            pools.add(LOWER_CHARS)
            guaranteed.add(LOWER_CHARS[random.nextInt(LOWER_CHARS.length)])
        }
        if (nums) {
            pools.add(NUMBER_CHARS)
            guaranteed.add(NUMBER_CHARS[random.nextInt(NUMBER_CHARS.length)])
        }
        if (syms) {
            pools.add(SYMBOL_CHARS)
            guaranteed.add(SYMBOL_CHARS[random.nextInt(SYMBOL_CHARS.length)])
        }

        if (pools.isEmpty()) {
            return ""
        }

        val targetLength = length.coerceAtLeast(guaranteed.size)
        val combinedPool = pools.joinToString("")
        val result = ArrayList<Char>(targetLength)
        result.addAll(guaranteed)

        val remaining = targetLength - guaranteed.size
        for (i in 0 until remaining) {
            result.add(combinedPool[random.nextInt(combinedPool.length)])
        }

        // Fisher-Yates shuffle using SecureRandom
        for (i in result.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val temp = result[i]
            result[i] = result[j]
            result[j] = temp
        }

        return result.joinToString("")
    }
}
