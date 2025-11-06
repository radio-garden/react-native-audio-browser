package com.audiobrowser.browser

import org.junit.Test
import org.junit.Assert.*

class SimpleRouterTest {
    
    private val router = SimpleRouter()
    
    @Test
    fun `exact match works`() {
        val routes = mapOf("/artists" to "config1")
        val result = router.findBestMatch("/artists", routes)
        
        assertNotNull(result)
        assertEquals("/artists", result!!.first)
        assertTrue(result.second.params.isEmpty())
    }
    
    @Test
    fun `parameter extraction works`() {
        val routes = mapOf("/artists/{id}" to "config1")
        val result = router.findBestMatch("/artists/123", routes)
        
        assertNotNull(result)
        assertEquals("/artists/{id}", result!!.first)
        assertEquals("123", result.second.params["id"])
    }
    
    @Test
    fun `multiple parameters work`() {
        val routes = mapOf("/artists/{artistId}/albums/{albumId}" to "config1")
        val result = router.findBestMatch("/artists/123/albums/456", routes)
        
        assertNotNull(result)
        assertEquals("123", result!!.second.params["artistId"])
        assertEquals("456", result.second.params["albumId"])
    }
    
    @Test
    fun `specificity works - constants win over parameters`() {
        val routes = mapOf(
            "/artists/{id}" to "generic",
            "/artists/popular" to "specific"
        )
        val result = router.findBestMatch("/artists/popular", routes)
        
        assertEquals("/artists/popular", result!!.first)
    }
    
    @Test
    fun `specificity works - more segments win`() {
        val routes = mapOf(
            "/artists" to "short",
            "/artists/premium" to "longer"
        )
        
        val result1 = router.findBestMatch("/artists", routes)
        assertEquals("/artists", result1!!.first)
        
        val result2 = router.findBestMatch("/artists/premium", routes)
        assertEquals("/artists/premium", result2!!.first)
    }
    
    @Test
    fun `no match returns null`() {
        val routes = mapOf("/artists" to "config1")
        val result = router.findBestMatch("/songs", routes)
        
        assertNull(result)
    }
    
    @Test
    fun `different segment count returns null`() {
        val routes = mapOf("/artists/{id}" to "config1")
        val result = router.findBestMatch("/artists/123/extra", routes)
        
        assertNull(result)
    }
    
    @Test
    fun `single wildcard matching works`() {
        val routes = mapOf("/artists/*" to "wildcard")
        val result = router.findBestMatch("/artists/anything", routes)
        
        assertNotNull(result)
        assertEquals("/artists/*", result!!.first)
        assertTrue(result.second.params.isEmpty()) // Wildcards don't capture
    }
    
    @Test
    fun `tail wildcard matching works`() {
        val routes = mapOf("/files/**" to "allFiles")
        
        val result1 = router.findBestMatch("/files/docs/readme.md", routes)
        assertNotNull(result1)
        assertEquals("docs/readme.md", result1!!.second.params["tail"])
        
        val result2 = router.findBestMatch("/files/images/2023/vacation.jpg", routes)
        assertNotNull(result2)
        assertEquals("images/2023/vacation.jpg", result2!!.second.params["tail"])
        
        // Also matches shorter paths
        val result3 = router.findBestMatch("/files", routes)
        assertNotNull(result3)
        assertFalse(result3!!.second.params.containsKey("tail")) // No tail captured
    }
    
    @Test
    fun `wildcard specificity works correctly`() {
        val routes = mapOf(
            "/artists/*" to "wildcard",
            "/artists/{id}" to "parameter", 
            "/artists/popular" to "constant"
        )
        
        // Constant wins
        val result1 = router.findBestMatch("/artists/popular", routes)
        assertEquals("/artists/popular", result1!!.first)
        
        // Parameter wins over wildcard for non-constant values
        val result2 = router.findBestMatch("/artists/123", routes)
        assertEquals("/artists/{id}", result2!!.first)
        assertEquals("123", result2.second.params["id"])
    }
    
    @Test
    fun `tail wildcard specificity works`() {
        val routes = mapOf(
            "/api/**" to "catchAll",
            "/api/users" to "specific",
            "/api/users/{id}" to "userById"
        )
        
        // Specific routes win over tail wildcard
        val result1 = router.findBestMatch("/api/users", routes)
        assertEquals("/api/users", result1!!.first)
        
        val result2 = router.findBestMatch("/api/users/123", routes)
        assertEquals("/api/users/{id}", result2!!.first)
        
        // Tail wildcard catches unmatched paths
        val result3 = router.findBestMatch("/api/posts/recent", routes)
        assertEquals("/api/**", result3!!.first)
        assertEquals("posts/recent", result3.second.params["tail"])
    }
    
    @Test
    fun `example from plan works`() {
        val routes = mapOf(
            "/artists" to "generic",
            "/artists/{id}" to "byId", 
            "/artists/premium" to "premium"
        )
        
        // Test each case
        val result1 = router.findBestMatch("/artists", routes)
        assertEquals("/artists", result1!!.first)
        
        val result2 = router.findBestMatch("/artists/123", routes)
        assertEquals("/artists/{id}", result2!!.first)
        assertEquals("123", result2.second.params["id"])
        
        val result3 = router.findBestMatch("/artists/premium", routes)
        assertEquals("/artists/premium", result3!!.first) // More specific than {id}
    }
}