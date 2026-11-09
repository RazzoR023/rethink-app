package com.celzero.bravedns.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMigrationsTest {

    @Test
    fun freshInstall_runsNoMigration() {
        assertFalse(UpdateMigrations.resetDownloader(0))
        assertFalse(UpdateMigrations.migrateSmartDns(0))
    }

    @Test
    fun downloaderReset_onlyForUpdatesFromBeforeV055u() {
        assertTrue(UpdateMigrations.resetDownloader(52))
        assertFalse(UpdateMigrations.resetDownloader(53))
        assertFalse(UpdateMigrations.resetDownloader(71)) // used to run on every update
    }

    @Test
    fun smartDnsMigration_onlyForUpdatesFromV057OrOlder() {
        assertTrue(UpdateMigrations.migrateSmartDns(66))
        assertTrue(UpdateMigrations.migrateSmartDns(67))
        assertFalse(UpdateMigrations.migrateSmartDns(68))
        assertFalse(UpdateMigrations.migrateSmartDns(71)) // used to run on every update
    }
}
