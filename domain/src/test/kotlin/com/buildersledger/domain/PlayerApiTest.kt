package com.buildersledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlayerApiTest {
    // Hand-written, shaped like the documented response; NOT captured from the live API.
    private val full = """
    {
      "tag": "#2PP", "name": "Tester", "townHallLevel": 12, "townHallWeaponLevel": 3,
      "expLevel": 150, "trophies": 3100, "bestTrophies": 3300, "warStars": 420,
      "builderHallLevel": 7, "builderBaseTrophies": 2500,
      "clan": {"tag": "#CLAN", "name": "Fan Club", "clanLevel": 10, "badgeUrls": {"small": "x"}},
      "labels": [{"id": 1, "name": "Farming"}],
      "achievements": [{"name": "Bigger Coffers", "stars": 3, "value": 10, "target": 10}],
      "someFutureField": {"a": [1, 2, 3]},
      "troops": [
        {"name": "Barbarian", "level": 8, "maxLevel": 9, "village": "home"},
        {"name": "Archer", "level": 9, "maxLevel": 9, "village": "home"},
        {"name": "Dragon", "level": 5, "maxLevel": 7, "village": "home"},
        {"name": "Super Barbarian", "level": 8, "maxLevel": 9, "village": "home", "superTroopIsActive": true},
        {"name": "Sneaky Goblin", "level": 7, "maxLevel": 8, "village": "home", "superTroopIsActive": false},
        {"name": "Wall Wrecker", "level": 3, "maxLevel": 4, "village": "home"},
        {"name": "Raged Barbarian", "level": 10, "maxLevel": 18, "village": "builderBase"},
        {"name": "Sneaky Archer", "level": 18, "maxLevel": 18, "village": "builderBase"}
      ],
      "heroes": [
        {"name": "Barbarian King", "level": 60, "maxLevel": 65, "village": "home",
         "equipment": [{"name": "Giant Gauntlet", "level": 10, "maxLevel": 18}]},
        {"name": "Battle Machine", "level": 25, "maxLevel": 30, "village": "builderBase"}
      ],
      "heroEquipment": [
        {"name": "Giant Gauntlet", "level": 10, "maxLevel": 18, "village": "home"},
        {"name": "Rage Vial", "level": 18, "maxLevel": 18, "village": "home"}
      ],
      "spells": [
        {"name": "Lightning Spell", "level": 6, "maxLevel": 9, "village": "home"},
        {"name": "Rage Spell", "level": 5, "maxLevel": 6, "village": "home"}
      ]
    }
    """.trimIndent()

    private fun parsed() = PlayerApi.parse(full)

    @Test fun basics() {
        val p = parsed()
        assertEquals("Tester", p.name)
        assertEquals("#2PP", p.tag)
        assertEquals(12, p.townHall)
        assertEquals(7, p.builderHall)
        assertEquals(3100, p.trophies)
        assertEquals("Fan Club", p.clanName)
    }

    @Test fun classifiesKinds() {
        val p = parsed()
        assertEquals(ItemKind.SIEGE, p.items.first { it.name == "Wall Wrecker" }.kind)
        assertEquals(ItemKind.TROOP, p.items.first { it.name == "Dragon" }.kind)
        assertEquals(ItemKind.HERO, p.items.first { it.name == "Barbarian King" }.kind)
        assertEquals(ItemKind.SPELL, p.items.first { it.name == "Rage Spell" }.kind)
        assertEquals(ItemKind.EQUIPMENT, p.items.first { it.name == "Rage Vial" }.kind)
    }

    @Test fun builderBaseItemsAreSeparated() {
        val p = parsed()
        val bb = p.counted(ApiVillage.BUILDER_BASE).map { it.name }.toSet()
        assertEquals(setOf("Raged Barbarian", "Sneaky Archer", "Battle Machine"), bb)
        assertFalse("Dragon" in bb)
    }

    @Test fun superTroopsAreFlaggedAndExcluded() {
        val p = parsed()
        val sb = p.items.first { it.name == "Super Barbarian" }
        assertTrue(sb.superBoost); assertTrue(sb.superActive)
        val sg = p.items.first { it.name == "Sneaky Goblin" }
        assertTrue(sg.superBoost); assertFalse(sg.superActive)
        assertTrue(p.counted().none { it.superBoost })
    }

    @Test fun equipmentIsNotDoubleCounted() {
        val p = parsed()
        assertEquals(1, p.items.count { it.name == "Giant Gauntlet" })
    }

    @Test fun nestedEquipmentUsedWhenTopLevelMissing() {
        val p = PlayerApi.parse(
            """{"heroes":[{"name":"King","level":1,"maxLevel":5,"village":"home","equipment":[{"name":"Vial","level":2,"maxLevel":4}]}]}"""
        )
        val e = p.items.single { it.kind == ItemKind.EQUIPMENT }
        assertEquals("Vial", e.name); assertEquals(ApiVillage.HOME, e.village); assertEquals(2, e.level)
    }

    @Test fun remainingHelpers() {
        val home = parsed().remaining(ApiVillage.HOME, ItemKind.TROOP)
        // Barbarian 8/9, Archer 9/9, Dragon 5/7 (super troops excluded)
        assertEquals(3, home.total)
        assertEquals(1, home.maxed)
        assertEquals(1 + 0 + 2, home.levelsLeft)
        assertEquals(22, home.levelsReached)
        assertEquals(25, home.levelsAvailable)
        assertEquals(88, home.percent)
    }

    @Test fun byKindOmitsEmptyKindsAndOverallCombines() {
        val p = parsed()
        val kinds = p.byKind(ApiVillage.HOME)
        assertTrue(ItemKind.SIEGE in kinds && ItemKind.SPELL in kinds)
        val overall = p.remaining()
        assertEquals(p.counted().size, overall.total)
        assertTrue(overall.percent in 1..99)
        val onlyBb = PlayerApi.parse("""{"troops":[{"name":"X","level":1,"maxLevel":2,"village":"home"}]}""").byKind(ApiVillage.BUILDER_BASE)
        assertTrue(onlyBb.isEmpty())
    }

    @Test fun closestToMaxRanking() {
        val ranked = parsed().closestToMax(ApiVillage.HOME).map { it.name }
        // levels left: Barbarian 1, Wall Wrecker 1, Rage Spell 1, Dragon 2, ... ; maxed items excluded
        assertFalse("Archer" in ranked)
        assertFalse("Rage Vial" in ranked)
        // 1 level left each; further along first: 8/9, 5/6, 3/4
        assertEquals(listOf("Barbarian", "Rage Spell", "Wall Wrecker"), ranked.take(3))
        val levelsLeft = parsed().closestToMax(ApiVillage.HOME, 50).map { it.levelsLeft }
        assertEquals(levelsLeft.sorted(), levelsLeft)
        assertEquals(2, parsed().closestToMax(null, 2).size)
        assertTrue(parsed().closestToMax(null, 0).isEmpty())
        assertEquals("Barbarian", ranked.first())
    }

    @Test fun missingFieldsNeverCrash() {
        val p = PlayerApi.parse("""{"troops":[{"name":"Mystery"},{"level":3},{"name":"NoMax","level":4}],"spells":[{"name":"S","level":"3","maxLevel":"5.0"}]}""")
        assertNull(p.name); assertNull(p.townHall); assertNull(p.clanName)
        val mystery = p.items.first { it.name == "Mystery" }
        assertEquals(0, mystery.level); assertEquals(ApiVillage.HOME, mystery.village)
        assertFalse(mystery.counts)
        val spell = p.items.first { it.name == "S" }
        assertEquals(3, spell.level); assertEquals(5, spell.maxLevel)
        assertEquals(1, p.remaining().total) // only the spell has a known max
        assertEquals(0, PlayerApi.parse("""{"troops":[{"name":"NoMax","level":4}]}""").remaining().percent)
    }

    @Test fun wrongTypesAreIgnored() {
        val p = PlayerApi.parse(
            """{"name":123,"townHallLevel":"13","troops":"nope","spells":{"a":1},"heroes":[1,null,"x",{"name":null},{"name":"H","level":null,"maxLevel":true}],"clan":[]}"""
        )
        assertEquals("123", p.name)
        assertEquals(13, p.townHall)
        assertEquals(1, p.items.size)
        assertEquals(0, p.items[0].level)
        assertNull(p.clanName)
    }

    @Test fun zeroTownHallBecomesNull() {
        assertNull(PlayerApi.parse("""{"townHallLevel":0}""").townHall)
    }

    @Test fun emptyArraysAndEmptyObject() {
        val p = PlayerApi.parse("""{"troops":[],"heroes":[],"spells":[],"heroEquipment":[]}""")
        assertTrue(p.items.isEmpty())
        assertEquals(0, p.remaining().percent)
        assertTrue(p.closestToMax().isEmpty())
        assertTrue(PlayerApi.parse("{}").items.isEmpty())
    }

    @Test fun levelAboveMaxIsClamped() {
        val r = PlayerApi.parse("""{"spells":[{"name":"S","level":12,"maxLevel":10}]}""").remaining()
        assertEquals(100, r.percent); assertEquals(0, r.levelsLeft); assertEquals(1, r.maxed)
    }

    @Test fun villageSpellingsAreTolerated() {
        val p = PlayerApi.parse(
            """{"troops":[{"name":"A","level":1,"maxLevel":2,"village":"builder_base"},{"name":"B","level":1,"maxLevel":2,"village":"BuilderBase"},{"name":"C","level":1,"maxLevel":2,"village":"mars"}]}"""
        )
        assertEquals(listOf(ApiVillage.BUILDER_BASE, ApiVillage.BUILDER_BASE, ApiVillage.HOME), p.items.map { it.village })
    }

    @Test fun nonObjectResponsesThrowBadResponse() {
        for (bad in listOf("", "   ", "<html>proxy login</html>", "[1,2]", "null", "\"hi\"", "{not json")) {
            try {
                PlayerApi.parse(bad)
                fail("should have thrown for: $bad")
            } catch (e: ApiException) {
                assertEquals(ApiError.BadResponse, e.error)
            }
        }
    }

    @Test fun parseToleratesWhitespaceAndBom() {
        assertEquals("A", PlayerApi.parse("  \n{\"name\":\"A\"}\n ").name)
    }

    // ---- tags ----

    @Test fun normalizeTag() {
        assertEquals("#2PP0", PlayerApi.normalizeTag(" #2pp0 "))
        assertEquals("#2PP0", PlayerApi.normalizeTag("2PPO"))
        assertEquals("#2PP0", PlayerApi.normalizeTag("2ppo"))
        assertEquals("#8LQ29", PlayerApi.normalizeTag("##8lq29"))
        assertNull(PlayerApi.normalizeTag(null))
        assertNull(PlayerApi.normalizeTag(""))
        assertNull(PlayerApi.normalizeTag("#"))
        assertNull(PlayerApi.normalizeTag("#AB"))          // A, B not allowed, too short
        assertNull(PlayerApi.normalizeTag("#2PP 0"))       // inner space
        assertNull(PlayerApi.normalizeTag("#2PP1"))        // 1 not allowed
        assertNull(PlayerApi.normalizeTag("#" + "2".repeat(16)))
    }

    // ---- URLs ----

    @Test fun urlBuilder() {
        assertEquals("https://api.clashofclans.com/v1/players/%232PP0", PlayerApi.buildUrl(PlayerApi.DEFAULT_BASE_URL, "#2pp0"))
        assertEquals("https://cocproxy.royaleapi.dev/v1/players/%232PP0", PlayerApi.buildUrl(" https://cocproxy.royaleapi.dev/v1/ ", "2PP0"))
        assertEquals("https://192.168.1.5:8443/players/%232PP0", PlayerApi.buildUrl("https://192.168.1.5:8443", "#2PP0"))
        assertNull(PlayerApi.buildUrl("http://example.com/v1", "#2PP0")) // cleartext refused
        assertNull(PlayerApi.buildUrl("ftp://x/v1", "#2PP0"))
        assertNull(PlayerApi.buildUrl("api.example.com/v1", "#2PP0"))
        assertNull(PlayerApi.buildUrl("https://", "#2PP0"))
        assertNull(PlayerApi.buildUrl("https://a b/v1", "#2PP0"))
        assertNull(PlayerApi.buildUrl(PlayerApi.DEFAULT_BASE_URL, "#ZZZ"))
        assertNull(PlayerApi.buildUrl(null, "#2PP0"))
    }

    // ---- errors ----

    @Test fun errorMapping() {
        assertTrue(ApiError.fromStatus(403, """{"reason":"accessDenied.invalidIp","message":"Invalid authorization"}""") is ApiError.Forbidden)
        val f = ApiError.fromStatus(403, """{"reason":"accessDenied.invalidIp","message":"Invalid authorization"}""")
        assertTrue(f.message.contains("IP"))
        assertTrue(f.message.contains("proxy"))
        assertTrue(f.message.contains("accessDenied.invalidIp"))
        assertEquals(ApiError.NotFound, ApiError.fromStatus(404, null))
        assertEquals(ApiError.Throttled, ApiError.fromStatus(429, ""))
        assertEquals(ApiError.Maintenance, ApiError.fromStatus(503, "garbage"))
        val other = ApiError.fromStatus(500, """{"message":"boom"}""")
        assertTrue(other is ApiError.Http && other.message.contains("500") && other.message.contains("boom"))
        assertTrue(ApiError.fromStatus(403, "<html>").message.contains("403"))
    }

    @Test fun everyErrorHasAMessage() {
        val all = listOf(
            ApiError.NoKey, ApiError.BadTag, ApiError.BadBaseUrl, ApiError.Forbidden(null), ApiError.NotFound,
            ApiError.Throttled, ApiError.Maintenance, ApiError.Http(418, null), ApiError.Offline, ApiError.BadResponse,
        )
        assertTrue(all.all { it.message.isNotBlank() })
        assertEquals(all.size, all.map { it.message }.toSet().size)
    }

    @Test fun errorDetailIsTolerant() {
        assertNull(PlayerApi.errorDetail(null))
        assertNull(PlayerApi.errorDetail("[]"))
        assertEquals("r: m", PlayerApi.errorDetail("""{"reason":"r","message":"m","extra":1}"""))
        assertEquals("only", PlayerApi.errorDetail("""{"reason":"only","message":"only"}"""))
    }

    @Test fun acceptsLocalizedNameObjects() {
        val p = PlayerApi.parse("""{"townHallLevel":14,"troops":[{"name":{"en":"Barbarian"},"level":8,"maxLevel":10,"village":"home"}]}""")
        assertEquals("Barbarian", p.items.first().name)
    }
}
