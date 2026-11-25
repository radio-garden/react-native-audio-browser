package com.audiobrowser.util

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscardStaleArtworkTest {
  private lateinit var cacheDir: File

  @Before
  fun setUp() {
    cacheDir = Files.createTempDirectory("discard-stale-artwork").toFile()
  }

  @After
  fun tearDown() {
    cacheDir.deleteRecursively()
  }

  private fun filesUnderCache(): List<File> = cacheDir.walkTopDown().filter { it.isFile }.toList()

  private fun writeArtwork(dir: File, name: String) {
    dir.mkdirs()
    File(dir, name).writeText("png")
  }

  @Test
  fun `the files are deleted on the scope, not by the caller`() = runTest {
    writeArtwork(File(cacheDir, ArtworkContentProvider.ARTWORK_SUBDIR), "a.png")

    ArtworkContentProvider.discardStaleArtwork(
      cacheDir,
      CoroutineScope(StandardTestDispatcher(testScheduler)),
    )

    assertFalse(File(cacheDir, ArtworkContentProvider.ARTWORK_SUBDIR).exists())
    assertEquals(listOf("a.png"), filesUnderCache().map { it.name })
    advanceUntilIdle()
    assertEquals(emptyList<File>(), filesUnderCache())
  }

  @Test
  fun `a file written after the call survives the delete`() = runTest {
    val artworkDir = File(cacheDir, ArtworkContentProvider.ARTWORK_SUBDIR)
    writeArtwork(artworkDir, "old.png")

    ArtworkContentProvider.discardStaleArtwork(
      cacheDir,
      CoroutineScope(StandardTestDispatcher(testScheduler)),
    )
    writeArtwork(artworkDir, "new.png")
    advanceUntilIdle()

    assertEquals(listOf("new.png"), filesUnderCache().map { it.name })
  }

  @Test
  fun `a delete a previous process left unfinished is finished`() = runTest {
    writeArtwork(File(cacheDir, "${ArtworkContentProvider.ARTWORK_SUBDIR}-stale-1"), "left.png")

    ArtworkContentProvider.discardStaleArtwork(
      cacheDir,
      CoroutineScope(StandardTestDispatcher(testScheduler)),
    )
    advanceUntilIdle()

    assertEquals(emptyList<File>(), filesUnderCache())
  }
}
