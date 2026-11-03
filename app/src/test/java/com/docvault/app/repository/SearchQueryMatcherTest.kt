package com.docvault.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryMatcherTest {
    @Test fun exactPhraseIsReturnedForViewerHighlight() {
        assertEquals("safety relay", SearchQueryMatcher.matchingHighlight("Safety Relay", "A safety relay manual"))
    }

    @Test fun nonContiguousTermsReturnOneActuallyPresentTerm() {
        assertEquals("relay", SearchQueryMatcher.matchingHighlight("relay safety", "Safety features for the relay"))
    }

    @Test fun missingAnyTermReturnsNoMatch() {
        assertNull(SearchQueryMatcher.matchingHighlight("relay sensor", "Safety relay manual"))
    }
}
