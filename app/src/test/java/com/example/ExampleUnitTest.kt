package com.example

import com.example.core.moderation.ModerationResult
import com.example.core.moderation.VideoContentModerator
import com.example.data.remote.SupabaseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun normalizeSupabaseUrl_stripsSubpathsAndQuotes() {
    assertEquals(
      "https://abcdefghij.supabase.co",
      SupabaseConfig.normalizeSupabaseUrl("\"https://abcdefghij.supabase.co/rest/v1/\"")
    )
    assertEquals(
      "https://abcdefghij.supabase.co",
      SupabaseConfig.normalizeSupabaseUrl("abcdefghij.supabase.co/")
    )
  }

  @Test
  fun normalizeSupabaseUrl_convertsDashboardUrlToProjectHost() {
    assertEquals(
      "https://myprojref123.supabase.co",
      SupabaseConfig.normalizeSupabaseUrl("https://supabase.com/dashboard/project/myprojref123/editor")
    )
  }

  @Test
  fun videoContentModerator_allowsNormalVideoMetadata() {
    val result = VideoContentModerator.checkMetadataSafety(
      title = "Sunset Cricket Match in Dhaka",
      caption = "Great classic pass and goal! #cricket #dhaka #analysis",
      location = "Dhaka, Bangladesh",
      fileName = "VID_20261116_cricket.mp4"
    )
    assertTrue(result is ModerationResult.Safe)
  }

  @Test
  fun videoContentModerator_blocksExplicitPornMetadataAndFilename() {
    val blockedCaption = VideoContentModerator.checkMetadataSafety(
      title = "Check this out",
      caption = "Watch hot #xxx #porn video now",
      location = ""
    )
    assertTrue(blockedCaption is ModerationResult.Blocked)

    val blockedFile = VideoContentModerator.checkMetadataSafety(
      title = "My video",
      caption = "Fun clip",
      location = "",
      fileName = "leaked_sextape_18+.mp4"
    )
    assertTrue(blockedFile is ModerationResult.Blocked)
  }
}
