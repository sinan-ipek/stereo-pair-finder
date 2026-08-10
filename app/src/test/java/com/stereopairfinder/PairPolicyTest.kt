package com.stereopairfinder

import android.net.Uri
import com.stereopairfinder.model.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class PairPolicyTest {
    private fun photo(time:Long?, id:String)=Photo(mock(Uri::class.java),time,id,id)
    @Test fun `only overlapping adjacent chronological pairs are generated`() {
        val a=photo(3000,"a");val b=photo(1000,"b");val c=photo(2000,"c")
        val pairs=PairPolicy.adjacent(listOf(a,b,c))
        assertEquals(2,pairs.size);assertEquals(listOf("b","c"),listOf(pairs[0].left.label,pairs[0].right.label))
        assertSame(pairs[0].right,pairs[1].left);assertFalse(pairs.any{it.left===b&&it.right===a})
    }
    @Test fun `missing time is deterministic and cannot match`() {
        val pairs=PairPolicy.adjacent(listOf(photo(null,"z"),photo(null,"a")))
        assertEquals("a",pairs[0].left.label);assertEquals(PairStatus.TIME_MISSING,PairPolicy.status(pairs[0],100.0,true,true,100.0,1.0))
    }
    @Test fun `defaults and inclusive boundaries are enforced`() {
        assertEquals(15,DEFAULT_MAX_SECONDS);assertEquals(72,DEFAULT_SIMILARITY)
        val p=PairCandidate(1,photo(0,"a"),photo(15000,"b"))
        assertEquals(PairStatus.MATCHED,PairPolicy.status(p,72.0,true,true,60.0,.35))
        assertEquals(PairStatus.SIMILARITY_LOW,PairPolicy.status(p,71.9,true,true,100.0,1.0))
        assertEquals(PairStatus.TIME_EXCEEDED,PairPolicy.status(p.copy(right=photo(15001,"c")),100.0,true,true,100.0,1.0))
        assertEquals(PairStatus.ALIGNMENT_FAILED,PairPolicy.status(p,100.0,true,false,100.0,1.0))
    }
}
