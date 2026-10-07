package com.example.security

/** A vault operation that needs the vault key ran (or finished) while the vault was locked. */
class VaultLockedException : IllegalStateException("The vault is locked")
