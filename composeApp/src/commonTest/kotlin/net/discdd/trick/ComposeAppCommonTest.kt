package net.discdd.trick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.BeforeTest
import kotlin.test.AfterTest

class ComposeButtonUiTest {

    private lateinit var numbers: MutableList<Int>

    @BeforeTest
    fun setUp() {
        println("BeforeTest is running :)")
        numbers = mutableListOf<Int>()
    }

    @Test
    fun testAddNumber() {
        numbers.add(69)
        assertEquals(1, numbers.size)
        assertEquals(listOf(69), numbers)
    }

    @Test
    fun testRemoveNumber() {
        numbers.add(1)
        numbers.removeAt(0)
        assertEquals(true,numbers.isEmpty())
    }

    @Test
    fun testIsEmpty() {
        assertEquals(true,numbers.isEmpty())
    }

    @AfterTest
    fun tearDown() {
        println("After test is running :)")
        numbers.clear()
    }

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }
}