package com.example.tourcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VisemeLipSyncTest {

    @Test
    fun testA_normalSpeechRanges() {
        // "Hello, where are you going?"
        val sentence = "Hello, where are you going?"
        // "Hello" contains 'o' -> O
        assertEquals(VisemeMapper.Viseme.O, VisemeMapper.map("Hello", sentence, 0, 5))
        // "where" contains 'w'/'e' -> O (rounded)
        assertEquals(VisemeMapper.Viseme.O, VisemeMapper.map("where", sentence, 7, 12))
        // "are" contains 'a' -> A
        assertEquals(VisemeMapper.Viseme.A, VisemeMapper.map("are", sentence, 13, 16))
        // "you" contains 'o'/'u' -> O
        assertEquals(VisemeMapper.Viseme.O, VisemeMapper.map("you", sentence, 17, 20))
        // "going" contains 'o' -> O
        assertEquals(VisemeMapper.Viseme.O, VisemeMapper.map("going", sentence, 21, 26))
    }

    @Test
    fun testB_punctuationAndPauses() {
        // "Hello! Where are you going? I can help."
        val text = "Hello! Where are you going? I can help."
        // Pure punctuation returns REST
        assertEquals(VisemeMapper.Viseme.REST, VisemeMapper.map("!", text, 5, 6))
        assertEquals(VisemeMapper.Viseme.REST, VisemeMapper.map("?", text, 26, 27))
        assertEquals(VisemeMapper.Viseme.REST, VisemeMapper.map(".", text, 38, 39))
        assertEquals(VisemeMapper.Viseme.REST, VisemeMapper.map("   ", text, 6, 7))
        assertEquals(VisemeMapper.Viseme.REST, VisemeMapper.map(""))
    }

    @Test
    fun testC_bilabialSounds() {
        // Words with M, B, P must prioritize Viseme.M (lips meet)
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("money"))
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("bank"))
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("police"))
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("problem"))
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("help"))
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("Semmozhi"))
    }

    @Test
    fun testD_vowelDistinction() {
        // A - open vowels
        assertEquals(VisemeMapper.Viseme.A, VisemeMapper.map("ask"))
        assertEquals(VisemeMapper.Viseme.A, VisemeMapper.map("card"))

        // E - spread vowels
        assertEquals(VisemeMapper.Viseme.E, VisemeMapper.map("city"))
        assertEquals(VisemeMapper.Viseme.E, VisemeMapper.map("see"))

        // O - rounded vowels
        assertEquals(VisemeMapper.Viseme.O, VisemeMapper.map("go"))
        assertEquals(VisemeMapper.Viseme.O, VisemeMapper.map("tour"))
    }

    @Test
    fun testE_trailingPunctuationRelaxation() {
        val sentence = "Stop. Now."
        // trailing punctuation index after word
        assertEquals(VisemeMapper.Viseme.M, VisemeMapper.map("Stop", sentence, 0, 4))
    }
}

