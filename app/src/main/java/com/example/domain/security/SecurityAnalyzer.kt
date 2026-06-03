package com.example.domain.security

import com.example.domain.models.VaultEntry

data class SecurityStatsSummary(
    val securityScore: Int,
    val totalPasswords: Int,
    val strongPasswordCount: Int,
    val mediumPasswordCount: Int,
    val weakPasswordCount: Int,
    val reusedPasswordCount: Int,
    val missingPasswordCount: Int,
    val securityStatus: String,
    val lastUpdatedTimestamp: Long
) {
    companion object {
        val Empty = SecurityStatsSummary(
            securityScore = 0,
            totalPasswords = 0,
            strongPasswordCount = 0,
            mediumPasswordCount = 0,
            weakPasswordCount = 0,
            reusedPasswordCount = 0,
            missingPasswordCount = 0,
            securityStatus = "No passwords stored",
            lastUpdatedTimestamp = 0L
        )
    }
}


data class SecurityStats(
    val securityScore: Int,
    val totalPasswords: Int,
    val strongPasswords: Int,
    val mediumPasswords: Int,
    val weakPasswords: Int,
    val reusedPasswords: Int,
    val missingPasswords: Int,
    val securityStatus: String,
    val weakEntryIds: Set<Int> = emptySet(),
    val reusedEntryIds: Set<Int> = emptySet(),
    val missingEntryIds: Set<Int> = emptySet(),
    val recommendations: List<SecurityRecommendation> = emptyList(),
    val passwordScores: Map<Int, Int> = emptyMap(),
    val passwordReasons: Map<Int, List<String>> = emptyMap()
) {
    companion object {
        val Empty = SecurityStats(
            securityScore = 0,
            totalPasswords = 0,
            strongPasswords = 0,
            mediumPasswords = 0,
            weakPasswords = 0,
            reusedPasswords = 0,
            missingPasswords = 0,
            securityStatus = "No passwords stored",
            weakEntryIds = emptySet(),
            reusedEntryIds = emptySet(),
            missingEntryIds = emptySet(),
            recommendations = emptyList(),
            passwordScores = emptyMap(),
            passwordReasons = emptyMap()
        )
    }
}

enum class RecommendationAction { FIX_WEAK, FIX_REUSED, FIX_MISSING }

data class SecurityRecommendation(
    val title: String,
    val description: String,
    val actionType: RecommendationAction,
    val priority: Int
)

object RecommendationEngine {
    fun generateRecommendations(weakCount: Int, reusedCount: Int, missingCount: Int): List<SecurityRecommendation> {
        val recommendations = mutableListOf<SecurityRecommendation>()

        if (missingCount > 0) {
            recommendations.add(
                SecurityRecommendation(
                    title = "Add $missingCount missing passwords",
                    description = "Incomplete entries are insecure and difficult to manage.",
                    actionType = RecommendationAction.FIX_MISSING,
                    priority = 0
                )
            )
        }
        
        if (weakCount > 0) {
            recommendations.add(
                SecurityRecommendation(
                    title = "Replace $weakCount weak passwords",
                    description = "Weak passwords are easily guessable. Update them to improve your security score.",
                    actionType = RecommendationAction.FIX_WEAK,
                    priority = if (weakCount > 5) 1 else 3
                )
            )
        }
        
        if (reusedCount > 0) {
            recommendations.add(
                SecurityRecommendation(
                    title = "Fix $reusedCount reused passwords",
                    description = "Reusing passwords increases risk across multiple accounts.",
                    actionType = RecommendationAction.FIX_REUSED,
                    priority = if (reusedCount > 3) 2 else 4
                )
            )
        }
        
        return recommendations.sortedBy { it.priority }
    }
}

object SecurityAnalyzer {

    fun analyze(entries: List<VaultEntry>): SecurityStats {
        if (entries.isEmpty()) return SecurityStats.Empty
        
        val total = entries.size

        var totalScore = 0
        var weakCount = 0
        var mediumCount = 0
        var strongCount = 0
        var missingCount = 0
        
        val weakEntryIds = mutableSetOf<Int>()
        val reusedEntryIds = mutableSetOf<Int>()
        val missingEntryIds = mutableSetOf<Int>()
        val passwordCounts = mutableMapOf<String, Int>()
        val passwordScores = mutableMapOf<Int, Int>()
        val passwordReasons = mutableMapOf<Int, List<String>>()

        // Pass 1: Count frequency of each password and calculate strength
        for (entry in entries) {
            val pwd = entry.password
            if (pwd.isEmpty()) {
                missingCount++
                missingEntryIds.add(entry.id)
                passwordScores[entry.id] = 0
                passwordReasons[entry.id] = listOf("Empty password")
                continue
            }

            passwordCounts[pwd] = (passwordCounts[pwd] ?: 0) + 1

            val score = scorePassword(pwd)
            totalScore += score
            passwordScores[entry.id] = score
            
            if (score <= 40) {
                weakCount++
                weakEntryIds.add(entry.id)
                passwordReasons[entry.id] = getWeaknessReasons(pwd)
            } else if (score <= 70) {
                mediumCount++
            } else {
                strongCount++
            }
        }

        // Pass 2: Calculate reuse
        var reusedCount = 0
        for (entry in entries) {
            val pwd = entry.password
            if (pwd.isNotEmpty() && (passwordCounts[pwd] ?: 0) > 1) {
                reusedCount++
                reusedEntryIds.add(entry.id)
            }
        }

        val analyzedPasswords = total - missingCount

        // Vault base score is average of all passwords
        val averageScore = if (analyzedPasswords > 0) totalScore / analyzedPasswords else 0

        // Reuse penalty: up to 30 points if 100% of passwords are reused
        val reuseRatio = if (analyzedPasswords > 0) reusedCount.toDouble() / analyzedPasswords else 0.0
        val reusePenalty = reuseRatio * 30.0
        
        val finalScore = (averageScore - reusePenalty).toInt().coerceIn(0, 100)

        val status = when {
            analyzedPasswords == 0 -> "Incomplete"
            finalScore in 81..100 -> "Excellent"
            finalScore in 61..80 -> "Good"
            finalScore in 41..60 -> "Fair"
            finalScore in 21..40 -> "Very Weak"
            else -> "Critical"
        }

        return SecurityStats(
            securityScore = finalScore,
            totalPasswords = total,
            strongPasswords = strongCount,
            mediumPasswords = mediumCount,
            weakPasswords = weakCount,
            reusedPasswords = reusedCount,
            missingPasswords = missingCount,
            securityStatus = status,
            weakEntryIds = weakEntryIds,
            reusedEntryIds = reusedEntryIds,
            missingEntryIds = missingEntryIds,
            recommendations = RecommendationEngine.generateRecommendations(weakCount, reusedCount, missingCount),
            passwordScores = passwordScores,
            passwordReasons = passwordReasons
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

    fun getWeaknessReasons(password: String): List<String> {
        if (password.isEmpty()) return listOf("Empty password")
        
        val reasons = mutableListOf<String>()
        if (password.length < 8) reasons.add("Too short")
        if (!password.any { it.isUpperCase() }) reasons.add("Missing uppercase letter")
        if (!password.any { it.isLowerCase() }) reasons.add("Missing lowercase letter")
        if (!password.any { it.isDigit() }) reasons.add("Missing number")
        if (!password.any { !it.isLetterOrDigit() }) reasons.add("Missing symbol")
        
        val commonPasswords = listOf("password", "123456", "12345678", "qwerty", "admin", "letmein", "password123")
        if (commonPasswords.contains(password.lowercase())) {
            reasons.add("Commonly used password")
        }

        val dictionaryWords = listOf("football", "dragon", "arsenal", "liverpool", "monkey", "sunshine", "iloveyou")
        if (dictionaryWords.any { password.lowercase().contains(it) }) {
            reasons.add("Contains common dictionary word")
        }

        val repeatedPattern = Regex("(.)\\1{2,}")
        if (repeatedPattern.containsMatchIn(password)) {
            reasons.add("Repeated characters detected")
        }

        val sequentialPatterns = listOf("12345", "qwerty", "abcdef", "98765", "asdfg")
        if (sequentialPatterns.any { password.lowercase().contains(it) }) {
            reasons.add("Sequential pattern detected")
        }

        return reasons
    }
}
