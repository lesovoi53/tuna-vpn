package com.wireturn.app.kernel

import com.google.gson.JsonParser
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.data.kernel.MasterDnsPoolSelection
import com.wireturn.app.domain.MasterDnsPoolMember
import com.wireturn.app.domain.MasterDnsPoolPlan
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MasterDnsPoolCommandTest {
    @Test fun eightIndependentConfigsReachMemoryLauncherWithoutCredentialFiles() {
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
            val frame = MemoryConfigs.frame(command, emptyMap())
            DataInputStream(ByteArrayInputStream(frame.bytes)).use { input ->
                fun field() = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                input.skipBytes(8)
                val args = List(input.readInt()) { field() }
                val files = linkedMapOf<String, String>()
                repeat(input.readInt()) { files[field()] = field() }
                assertEquals(17, files.size)
                val manifest = JsonParser.parseString(files.getValue(args[2])).asJsonObject
                val children = manifest["members"].asJsonArray.map { it.asJsonObject }
                assertEquals(8, children.map { it["listen"].asString }.distinct().size)
                assertEquals(8, children.map { it["password"].asString }.distinct().size)
                children.forEachIndexed { index, child ->
                    val toml = files.getValue(child["configPath"].asString)
                    assertTrue(toml.contains("synthetic-key-${index + 1}"))
                    assertTrue(toml.contains(child["password"].asString))
                    assertFalse(toml.contains("front-password"))
                    assertEquals("192.0.2.${index + 1}:53", files.getValue(child["resolversPath"].asString))
                }
                assertFalse(args.joinToString().contains("password"))
            }
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
