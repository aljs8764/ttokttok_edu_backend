package com.ttokttok.domain.child

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ChildTest {
    private val birth = LocalDate.of(2016, 3, 2)

    @Test
    fun `이름(공백 무시)과 생일이 같으면 합치기 후보`() {
        Child(ChildId.new(), "김 민준", birth).looksSameAs(Child(ChildId.new(), "김민준", birth)) shouldBe true
    }

    @Test
    fun `생일이 다르거나 없으면 후보 아님`() {
        Child(ChildId.new(), "김민준", birth).looksSameAs(Child(ChildId.new(), "김민준", birth.plusDays(1))) shouldBe false
        Child(ChildId.new(), "김민준", null).looksSameAs(Child(ChildId.new(), "김민준", null)) shouldBe false
    }

    @Test
    fun `자기 자신은 후보 아님`() {
        val c = Child(ChildId.new(), "김민준", birth)
        c.looksSameAs(c) shouldBe false
    }
}
