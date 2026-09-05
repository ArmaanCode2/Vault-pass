package com.example

import com.example.domain.security.PasswordGenerator
import org.junit.Assert.*
import org.junit.Test

class PasswordGeneratorTest {

    @Test
    fun generatePassword_respectsLength() {
        for (len in listOf(8, 12, 16, 20, 24, 32)) {
            val pwd = PasswordGenerator.generatePassword(
                length = len,
                upper = true,
                lower = true,
                nums = true,
                syms = true
            )
            assertEquals("Generated password length must match requested length", len, pwd.length)
        }
    }

    @Test
    fun generatePassword_guaranteedInclusion() {
        // Run multiple iterations to verify consistent inclusion
        repeat(50) {
            val pwd = PasswordGenerator.generatePassword(
                length = 16,
                upper = true,
                lower = true,
                nums = true,
                syms = true
            )
            assertTrue("Must contain uppercase", pwd.any { it in PasswordGenerator.UPPER_CHARS })
            assertTrue("Must contain lowercase", pwd.any { it in PasswordGenerator.LOWER_CHARS })
            assertTrue("Must contain digits", pwd.any { it in PasswordGenerator.NUMBER_CHARS })
            assertTrue("Must contain symbols", pwd.any { it in PasswordGenerator.SYMBOL_CHARS })
        }
    }

    @Test
    fun generatePassword_selectiveSets() {
        // Only uppercase and digits
        repeat(20) {
            val pwd = PasswordGenerator.generatePassword(
                length = 12,
                upper = true,
                lower = false,
                nums = true,
                syms = false
            )
            assertEquals(12, pwd.length)
            assertTrue("Must contain uppercase", pwd.any { it in PasswordGenerator.UPPER_CHARS })
            assertTrue("Must contain digits", pwd.any { it in PasswordGenerator.NUMBER_CHARS })
            assertFalse("Must NOT contain lowercase", pwd.any { it in PasswordGenerator.LOWER_CHARS })
            assertFalse("Must NOT contain symbols", pwd.any { it in PasswordGenerator.SYMBOL_CHARS })
        }
    }

    @Test
    fun generatePassword_emptySets_returnsEmptyString() {
        val pwd = PasswordGenerator.generatePassword(
            length = 16,
            upper = false,
            lower = false,
            nums = false,
            syms = false
        )
        assertEquals("", pwd)
    }

    @Test
    fun generatePassword_randomness() {
        val passwords = (1..50).map {
            PasswordGenerator.generatePassword(16, upper = true, lower = true, nums = true, syms = true)
        }.toSet()
        assertEquals("50 generation cycles should produce 50 unique passwords", 50, passwords.size)
    }
}
