package com.wireturn.app.data

import com.wireturn.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SelectionPropertyTest {
    @Test fun bestModeAlwaysChoosesEligibleExtremumAcrossShuffledMeasurements() {
        val random=Random(4217)
        for(type in listOf(GroupTestType.URL_TEST)) repeat(250) {
            val members=(0..7).map { "p$it" }
            val g=ConnectionGroup("g","G",ConnectionCategory.VPN,type,members)
            val results=members.mapIndexed { i,id -> GroupMeasurement(id,20L+i*13,100.0+i*71,1000) }
                .shuffled(random)
            val noisy=results+listOf(GroupMeasurement("foreign",0,1e9,1000),GroupMeasurement("p0",0,1e9,2001))
            assertEquals("p0",GroupSelection().choose(g,noisy,null,2000))
        }
    }
    @Test fun priorityModeIsIndependentOfMeasuredSpeedAndInputOrdering() {
        val random=Random(884)
        for(type in listOf(GroupTestType.URL_TEST)) repeat(250) {
            val members=(0..7).map { "p$it" }.shuffled(random)
            val g=ConnectionGroup("g","G",ConnectionCategory.VPN,type,members,selectionMode=GroupSelectionMode.PRIORITY)
            val results=members.drop(1).mapIndexed { i,id -> GroupMeasurement(id,1000L-i,10.0+i,1000) }.shuffled(random)
            assertEquals(members[1],GroupSelection().choose(g,results,null,2000))
        }
    }
    @Test fun freshnessBoundaryAndMobileFlagsDoNotChangeSelectionRules() {
        for(type in listOf(GroupTestType.URL_TEST)) for(mobile in listOf(true,false)) {
            val g=ConnectionGroup("g","G",ConnectionCategory.VPN,type,listOf("a","b"),freshnessSeconds=30,allowMobile=mobile)
            val samples=listOf(GroupMeasurement("a",1,999.0,1000),GroupMeasurement("b",20,10.0,1001))
            assertEquals("a",GroupSelection().choose(g,samples,null,31000))
            assertEquals("b",GroupSelection().choose(g,samples,null,31001))
            assertNull(GroupSelection().choose(g,samples,null,31002))
        }
    }
}
