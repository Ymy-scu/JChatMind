package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolTypeTest {

    @Test
    void shouldHaveFixedAndOptionalValues() {
        assertEquals(2, ToolType.values().length);
        assertEquals(ToolType.FIXED, ToolType.valueOf("FIXED"));
        assertEquals(ToolType.OPTIONAL, ToolType.valueOf("OPTIONAL"));
    }

    @Test
    void fixedShouldBeFirst() {
        assertEquals(ToolType.FIXED, ToolType.values()[0]);
    }

    @Test
    void terminateToolShouldBeFixed() {
        assertSame(ToolType.FIXED, ToolType.FIXED);
    }

    @Test
    void dataBaseToolShouldBeOptional() {
        assertSame(ToolType.OPTIONAL, ToolType.OPTIONAL);
    }
}
