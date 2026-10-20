package com.wireturn.app.kernel

import com.google.gson.JsonParser
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import com.wireturn.app.domain.MasterDnsPoolMember
import com.wireturn.app.domain.MasterDnsPoolPlan
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MasterDnsPoolCommandTest {
    @Test fun eightIndependentPrivateConfigsAreReadableAndRemovedTogether() {
        val directory = Files.createTempDirectory("masterdns-pool-test").toFile()
        try {
            val members = (1..8).map { i -> MasterDnsPoolMember("member-$i", "Server $i",
                MasterDnsConfig(domains = listOf("s$i.example.invalid"), key = "synthetic-key-$i",
                    resolvers = "192.0.2.$i:53")) }
            val config = ClientConfig(socksAddr = "127.0.0.1:18000", socksUser = "front-user",
                socksPass = "front-password", kernelConfig = KernelConfig.MasterDns(members.first().config.copy(
                    localPool = MasterDnsPoolSelection(members.map { it.id }))),
                masterDnsPoolPlan = MasterDnsPoolPlan("least_connections", members))
            val ctx = object : KernelCommandContext {
                override val filesDir = directory
                override val nativeLibraryDir = "/native"
            }
            val command = MasterDnsKernel.buildCommand(ctx, config)
            assertEquals("-pool", command[1])
            val manifest = JsonParser.parseString(File(command[2]).readText()).asJsonObject
            val children = manifest["members"].asJsonArray.map { it.asJsonObject }
            assertEquals(8, children.map { it["listen"].asString }.distinct().size)
            assertEquals(8, children.map { it["password"].asString }.distinct().size)
            children.forEachIndexed { index, child ->
                val toml = File(child["configPath"].asString).readText()
                assertTrue(toml.contains("synthetic-key-${index + 1}"))
                assertTrue(toml.contains(child["password"].asString))
                assertFalse(toml.contains("front-password"))
                assertEquals("192.0.2.${index + 1}:53", File(child["resolversPath"].asString).readText())
            }
            assertFalse(command.joinToString().contains("password"))
            assertEquals(17, directory.walkTopDown().count { it.isFile })
            MemoryConfigs.removeDirectory(directory)
            assertFalse(directory.walkTopDown().any { it.isFile })
        } finally {
            MemoryConfigs.removeDirectory(directory)
            directory.deleteRecursively()
        }
    }

    @Test fun unresolvedPoolNeverStartsSingleAnchorInstead() {
        val ctx = object : KernelCommandContext {
            override val filesDir = File("unused")
            override val nativeLibraryDir = "/native"
        }
        val config = ClientConfig(kernelConfig = KernelConfig.MasterDns(MasterDnsConfig(
            localPool = MasterDnsPoolSelection(listOf("a", "b")))))
        try { MasterDnsKernel.buildCommand(ctx, config); fail("Missing plan accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("состав")) }
    }
}

