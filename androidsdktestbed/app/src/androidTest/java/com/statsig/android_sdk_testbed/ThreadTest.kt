package com.statsig.android_sdk_testbed


import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.filters.LargeTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.AndroidJUnit4
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ThreadTest {

  @Rule
  @JvmField
  var mActivityTestRule = ActivityTestRule(MainActivity::class.java)

  private var recyclerView: ViewInteraction? = null
  private val sectionIndices = mapOf(
    "start" to 0,
    "gates" to 6,
    "logging" to 12,
    "configs" to 18,
    "experiments" to 24
  )

  @Before
  fun setup() {
    recyclerView = onView(
      allOf(
        withId(R.id.recycler_view),
        childAtPosition(
          withClassName(`is`("android.widget.LinearLayout")),
          0
        )
      )
    )

    tapItem(1)
  }

  @Test
  fun checkGate() {
    val index = sectionIndices["gates"] ?: 0
    tapItem(index + 1)
    tapItem(index + 2)
    tapItem(index + 3)
    tapItem(index + 4)
  }

  @Test
  fun checkLogging() {
    val index = sectionIndices["logging"] ?: 0
    tapItem(index + 1)
    tapItem(index + 2)
    tapItem(index + 3)
    tapItem(index + 4)
  }

  @Test
  fun checkConfigs() {
    val index = sectionIndices["configs"] ?: 0
    tapItem(index + 1)
    tapItem(index + 2)
    tapItem(index + 3)
    tapItem(index + 4)
  }

  @Test
  fun checkExperiments() {
    val index = sectionIndices["experiments"] ?: 0
    tapItem(index + 1)
    tapItem(index + 2)
    tapItem(index + 3)
    tapItem(index + 4)
  }

  @Test
  fun monkeyTest() {
    val keys = sectionIndices.keys.toTypedArray()
    for (i in 0..10) {
      val k = keys.random()
      val index = sectionIndices[k] ?: 0
      tapItem(index + 1)
    }
  }

  private fun tapItem(index: Int) {
    recyclerView?.perform(actionOnItemAtPosition<ViewHolder>(index, click()))
  }

  private fun childAtPosition(
    parentMatcher: Matcher<View>, position: Int
  ): Matcher<View> {
    return object : TypeSafeMatcher<View>() {
      override fun describeTo(description: Description) {
        description.appendText("Child at position $position in parent ")
        parentMatcher.describeTo(description)
      }

      public override fun matchesSafely(view: View): Boolean {
        val parent = view.parent
        return parent is ViewGroup && parentMatcher.matches(parent)
            && view == parent.getChildAt(position)
      }
    }
  }
}
