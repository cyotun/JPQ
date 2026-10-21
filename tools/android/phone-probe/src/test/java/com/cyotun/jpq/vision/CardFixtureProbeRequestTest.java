package com.cyotun.jpq.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CardFixtureProbeRequestTest {
    @Test public void requiresExplicitOptInAndValidCaseAndOrientation() {
        assertNull(CardFixtureProbeRequest.parse(false,1,"landscape",null));
        assertNull(CardFixtureProbeRequest.parse(true,null,"landscape",null));
        assertNull(CardFixtureProbeRequest.parse(true,0,"landscape",null));
        assertNull(CardFixtureProbeRequest.parse(true,5,"landscape",null));
        assertNull(CardFixtureProbeRequest.parse(true,1,"sideways",null));
        assertNull(CardFixtureProbeRequest.parse(true,1,null,null));
    }

    @Test public void defaultsTimeoutAndAcceptsInclusiveBounds() {
        CardFixtureProbeRequest defaults=CardFixtureProbeRequest.parse(true,4,"portrait",null);
        assertNotNull(defaults);
        assertTrue(defaults.portrait);
        assertEquals(4,defaults.caseIndex);
        assertEquals(180,defaults.timeoutSeconds);
        assertEquals(15,CardFixtureProbeRequest.parse(true,1,"landscape",15).timeoutSeconds);
        assertEquals(300,CardFixtureProbeRequest.parse(true,1,"landscape",300).timeoutSeconds);
    }

    @Test public void rejectsTimeoutOutsideBounds() {
        assertNull(CardFixtureProbeRequest.parse(true,1,"landscape",14));
        assertNull(CardFixtureProbeRequest.parse(true,1,"landscape",301));
    }
}
