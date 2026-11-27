import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What the agent answers, checked before it replaces the safety check's word. */
class LookTest {

    static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return Board.MAPPER.readTree(s);
    }

    @Test
    void aVerdictIsKeptAsLooked() throws Exception {
        SafetyCheck.Check c = Look.parse("Bump x from 1 to 2", json("{\"verdict\":\"Safe\",\"reason\":\" Only in\\n tests. \"}"), Prs.NOW);
        assertEquals("safe", c.verdict());
        assertEquals("Only in tests.", c.reason());
        assertTrue(c.looked());
    }

    @Test
    void anythingElseIsNoVerdict() throws Exception {
        assertNull(Look.parse("t", json("{\"verdict\":\"merge it\",\"reason\":\"x\"}"), Prs.NOW));
        assertNull(Look.parse("t", json("{}"), Prs.NOW));
    }

    @Test
    void theReasonIsCut() throws Exception {
        SafetyCheck.Check c = Look.parse("t", json("{\"verdict\":\"careful\",\"reason\":\"" + "a".repeat(400) + "\"}"), Prs.NOW);
        assertEquals(240, c.reason().length());
    }

    @Test
    void onlyItsOwnIds() {
        assertTrue(Look.ID.matcher("look-1792714672622").matches());
        assertTrue(!Look.ID.matcher("reply-1792714672622").matches());
    }
}
