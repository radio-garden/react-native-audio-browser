package com.audiobrowser.routing

/**
 * Represents a successful route match with extracted parameters and specificity score.
 * 
 * @param params Extracted path parameters (e.g., {id} → "123")
 * @param specificity Score for route priority - higher means more specific
 */
data class RouteMatch(
    val params: Map<String, String>,
    val specificity: Int
)