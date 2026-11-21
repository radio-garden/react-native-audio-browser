package com.audiobrowser.browser

import com.audiobrowser.TestFixtures.resolvedTrack
import com.audiobrowser.TestFixtures.staticRoute
import com.audiobrowser.navigationErrorFor
import com.audiobrowser.util.JsPromise
import com.margelo.nitro.audiobrowser.BrowseResult
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams
import com.margelo.nitro.audiobrowser.Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam
import com.margelo.nitro.audiobrowser.NativeRouteEntry
import com.margelo.nitro.audiobrowser.NavigationError
import com.margelo.nitro.audiobrowser.NavigationErrorType
import com.margelo.nitro.audiobrowser.Track
import com.margelo.nitro.audiobrowser.TransformableRequestConfig
import com.margelo.nitro.audiobrowser.Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A JS callback that throws or rejects fails the navigation with `callback-error`. */
@RunWith(RobolectricTestRunner::class)
class BrowserManagerCallbackErrorTest {

  private val browserManager = BrowserManager()

  private fun navigationErrorOf(block: suspend () -> Unit): NavigationError =
    navigationErrorFor(assertThrows(Exception::class.java) { runBlocking { block() } })

  private fun browseRoute(
    path: String,
    answer: () -> Promise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>,
  ) =
    staticRoute(path, resolvedTrack(path = path))
      .copy(
        browseStatic = null,
        browseCallback =
          Func_std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError__std__shared_ptr_Promise_std__variant_ResolvedTrack__BrowseError_______BrowserSourceCallbackParam {
            answer()
          },
      )

  /** The bridge promise of a callback that throws before it returns. */
  private fun thrown(message: String) =
    JsPromise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>()
      .apply { reject(Exception(message)) }
      .promise

  @Test
  fun `a browse callback whose promise rejects fails with callback-error`() {
    val js = JsPromise<BrowseResult>().apply { reject(Exception("Error: token expired")) }
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(
            browseRoute("/a") {
              JsPromise<Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_>()
                .apply {
                  resolve(
                    Variant_ResolvedTrack_BrowseError_Promise_BrowseResult_.create(js.promise)
                  )
                }
                .promise
            }
          )
      )

    val error = navigationErrorOf { browserManager.resolve("/a") }

    assertEquals(NavigationErrorType.CALLBACK_ERROR, error.code)
    assertEquals("token expired", error.message)
  }

  @Test
  fun `a browse callback that throws fails with callback-error`() {
    browserManager.config =
      BrowserConfig(routes = arrayOf(browseRoute("/a") { thrown("token expired") }))

    val error = navigationErrorOf { browserManager.resolve("/a") }

    assertEquals(NavigationErrorType.CALLBACK_ERROR, error.code)
    assertEquals("token expired", error.message)
  }

  @Test
  fun `a search callback that rejects fails with callback-error`() {
    val js = JsPromise<Array<Track>>().apply { reject(Exception("TypeError: search is down")) }
    browserManager.config =
      BrowserConfig(
        routes =
          arrayOf(
            NativeRouteEntry(
              path = BrowserManager.SEARCH_ROUTE_PATH,
              browseCallback = null,
              browseConfig = null,
              browseStatic = null,
              searchCallback =
                Func_std__shared_ptr_Promise_std__shared_ptr_Promise_std__vector_Track______SearchParams {
                  JsPromise<Promise<Array<Track>>>().apply { resolve(js.promise) }.promise
                },
              searchConfig = null,
              media = null,
              artwork = null,
            )
          )
      )

    val error = navigationErrorOf { browserManager.search("jazz") }

    assertEquals(NavigationErrorType.CALLBACK_ERROR, error.code)
    assertEquals("search is down", error.message)
  }

  @Test
  fun `a request resolver that rejects fails with callback-error`() {
    val js =
      JsPromise<TransformableRequestConfig>().apply { reject(Exception("Error: signed out")) }
    browserManager.config =
      BrowserConfig(
        requestResolver =
          Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____ {
            JsPromise<Promise<TransformableRequestConfig>>().apply { resolve(js.promise) }.promise
          }
      )

    val error = navigationErrorOf { browserManager.resolve("/a") }

    assertEquals(NavigationErrorType.CALLBACK_ERROR, error.code)
    assertEquals("signed out", error.message)
  }

  @Test
  fun `a browse resolver that throws fails with callback-error`() {
    browserManager.config =
      BrowserConfig(
        browseResolver =
          Func_std__shared_ptr_Promise_std__shared_ptr_Promise_TransformableRequestConfig____ {
            JsPromise<Promise<TransformableRequestConfig>>()
              .apply { reject(Exception("signed out")) }
              .promise
          }
      )

    val error = navigationErrorOf { browserManager.resolve("/a") }

    assertEquals(NavigationErrorType.CALLBACK_ERROR, error.code)
    assertEquals("signed out", error.message)
  }
}
