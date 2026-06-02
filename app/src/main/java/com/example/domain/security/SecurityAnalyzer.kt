package com.example.domain.security

import com.example.domain.models.VaultEntry

data class SecurityStats(
    val totalPasswords: Int,
    val weakPasswords: Int,
    val reusedPasswords: Int,
    val securityScore: Int,
    val securityStatus: String
)

object SecurityAnalyzer {

    fun analyze(entries: List<VaultEntry>): SecurityStats {
        if (entries.isEmpty()) {
            return SecurityStats(
                totalPasswords = 0,
                weakPasswords = 0,
                reusedPasswords = 0,
                securityScore = 0,
                securityStatus = "No passwords stored"
            )
        }

        val total = entries.size
        
        var weakCount = 0
        var totalScore = 0.0
        val passwordCounts = mutableMapOf<String, Int>()

        // Pass 1: Count frequency of each password
        for (entry in entries) {
            val pwd = entry.password
            if (pwd.isNotEmpty()) {
                passwordCounts[pwd] = (passwordCounts[pwd] ?: 0) + 1
            }
        }

        // Pass 2: Calculate individual scores and weak counts
        for (entry in entries) {
            val pwd = entry.password
            if (pwd.isNotEmpty()) {
                val score = scorePassword(pwd)
                totalScore += score
                
                // Keep the old definition of weak for the UI counter, or use score < 60
                if (score <= 40) {
                    weakCount++
                }
            } else {
                weakCount++ // empty is weak
            }
        }

        // Pass 3: Calculate reuse
        var reusedCount = 0
        for (entry in entries) {
            val pwd = entry.password
            if (pwd.isNotEmpty() && (passwordCounts[pwd] ?: 0) > 1) {
                reusedCount++
            }
        }

        // Vault base score is average of all passwords
        val averageScore = totalScore / total

        // Reuse penalty: up to 30 points if 100% of passwords are reused
        val reuseRatio = reusedCount.toDouble() / total
        val reusePenalty = reuseRatio * 30.0
        
        val finalScore = (averageScore - reusePenalty).toInt().coerceIn(0, 100)

        val status = when (finalScore) {
            in 81..100 -> "Excellent"
            in 61..80 -> "Good"
            in 41..60 -> "Fair"
            in 21..40 -> "Very Weak"
            else -> "Critical"
        }

        return SecurityStats(
            totalPasswords = total,
            weakPasswords = weakCount,
            reusedPasswords = reusedCount,
            securityScore = finalScore,
            securityStatus = status
        )
    }

    fun scorePassword(password: String): Int {
        if (password.isEmpty()) return 0

        // 1. Calculate Entropy Base Score
        var poolSize = 0
        if (password.any { it.isLowerCase() }) poolSize += 26
        if (password.any { it.isUpperCase() }) poolSize += 26
        if (password.any { it.isDigit() }) poolSize += 10
        if (password.any { !it.isLetterOrDigit() }) poolSize += 32

        val entropy = password.length * (if (poolSize > 0) Math.log(poolSize.toDouble()) / Math.log(2.0) else 0.0)
        
        // Map 80+ bits of entropy to 100 base score
        var score = (entropy / 80.0) * 100.0

        // 2. Heavy Deductions

        // A. Common passwords
        val commonPasswords = listOf("password", "123456", "12345678", "qwerty", "admin", "letmein", "password123")
        if (commonPasswords.contains(password.lowercase())) {
            score -= 100.0
        }

        // B. Dictionary words (simple examples for illustration, could be expanded)
        val dictionaryWords = listOf("football", "dragon", "arsenal", "liverpool", "monkey", "sunshine", "iloveyou")
        if (dictionaryWords.any { password.lowercase().contains(it) }) {
            score -= 50.0
        }

        // C. Repeated characters (e.g., aaaaaa, 11111)
        val repeatedPattern = Regex("(.)\\1{2,}")
        if (repeatedPattern.containsMatchIn(password)) {
            score -= 50.0
        }

        // D. Sequential patterns (e.g., 12345, abcde)
        val sequentialPatterns = listOf("12345", "qwerty", "abcdef", "98765", "asdfg")
        if (sequentialPatterns.any { password.lowercase().contains(it) }) {
            score -= 50.0
        }

        return score.toInt().coerceIn(0, 100)
    }
}
