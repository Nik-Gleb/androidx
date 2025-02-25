/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.lazy.list

import android.os.Build
import androidx.compose.foundation.AutoTestFrameClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.testutils.WithTouchSlop
import androidx.compose.testutils.assertPixels
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@LargeTest
@RunWith(Parameterized::class)
/**
 * This class contains all LazyColumn-specific tests, as well as (by convention) tests that don't
 * need to be run in both orientations.
 *
 * To have a test run in both orientations (LazyRow and LazyColumn), add it to [LazyListTest]
 */
class LazyColumnTest(val useLookaheadScope: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "useLookahead = {0}")
        fun params() = arrayOf(true, false)
    }

    private val LazyListTag = "LazyListTag"

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun compositionsAreDisposed_whenDataIsChanged() {
        var composed = 0
        var disposals = 0
        val data1 = (1..3).toList()
        val data2 = (4..5).toList() // smaller, to ensure removal is handled properly

        var part2 by mutableStateOf(false)

        rule.setContentWithTestViewConfiguration {
            LazyColumn(
                Modifier
                    .testTag(LazyListTag)
                    .fillMaxSize()) {
                items(if (!part2) data1 else data2) {
                    DisposableEffect(NeverEqualObject) {
                        composed++
                        onDispose {
                            disposals++
                        }
                    }

                    Spacer(Modifier.height(50.dp))
                }
            }
        }

        rule.runOnIdle {
            assertWithMessage("Not all items were composed")
                .that(composed).isEqualTo(data1.size)
            composed = 0

            part2 = true
        }

        rule.runOnIdle {
            assertWithMessage(
                "No additional items were composed after data change, something didn't work"
            ).that(composed).isEqualTo(data2.size)

            // We may need to modify this test once we prefetch/cache items outside the viewport
            assertWithMessage(
                "Not enough compositions were disposed after scrolling, compositions were leaked"
            ).that(disposals).isEqualTo(data1.size)
        }
    }

    @Test
    fun compositionsAreDisposed_whenLazyListIsDisposed() {
        var emitLazyList by mutableStateOf(true)
        var disposeCalledOnFirstItem = false
        var disposeCalledOnSecondItem = false

        rule.setContentWithTestViewConfiguration {
            if (emitLazyList) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(2) {
                        Box(Modifier.requiredSize(100.dp))
                        DisposableEffect(Unit) {
                            onDispose {
                                if (it == 1) {
                                    disposeCalledOnFirstItem = true
                                } else {
                                    disposeCalledOnSecondItem = true
                                }
                            }
                        }
                    }
                }
            }
        }

        rule.runOnIdle {
            assertWithMessage("First item was incorrectly immediately disposed")
                .that(disposeCalledOnFirstItem).isFalse()
            assertWithMessage("Second item was incorrectly immediately disposed")
                .that(disposeCalledOnFirstItem).isFalse()
            emitLazyList = false
        }

        rule.runOnIdle {
            assertWithMessage("First item was not correctly disposed")
                .that(disposeCalledOnFirstItem).isTrue()
            assertWithMessage("Second item was not correctly disposed")
                .that(disposeCalledOnSecondItem).isTrue()
        }
    }

    @Test
    fun removeItemsTest() {
        var itemCount by mutableStateOf(3)
        val tag = "List"
        rule.setContentWithTestViewConfiguration {
            LazyColumn(Modifier.testTag(tag)) {
                items((0 until itemCount).toList()) {
                    BasicText("$it")
                }
            }
        }

        while (itemCount >= 0) {
            // Confirm the children's content
            for (i in 0 until 3) {
                rule.onNodeWithText("$i").apply {
                    if (i < itemCount) {
                        assertIsPlaced()
                    } else {
                        assertIsNotPlaced()
                    }
                }
            }
            itemCount--
        }
    }

    @Test
    fun changeItemsCountAndScrollImmediately() {
        lateinit var state: LazyListState
        var count by mutableStateOf(100)
        val composedIndexes = mutableListOf<Int>()
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp), state) {
                items(count) { index ->
                    composedIndexes.add(index)
                    Box(Modifier.size(20.dp))
                }
            }
        }

        rule.runOnIdle {
            composedIndexes.clear()
            count = 10
            runBlocking(AutoTestFrameClock()) {
                state.scrollToItem(50)
            }
            composedIndexes.forEach {
                assertThat(it).isLessThan(count)
            }
            assertThat(state.firstVisibleItemIndex).isEqualTo(9)
        }
    }

    @Test
    fun changingDataTest() {
        val dataLists = listOf(
            (1..3).toList(),
            (4..8).toList(),
            (3..4).toList()
        )
        var dataModel by mutableStateOf(dataLists[0])
        val tag = "List"
        rule.setContentWithTestViewConfiguration {
            LazyColumn(Modifier.testTag(tag)) {
                items(dataModel) {
                    BasicText("$it")
                }
            }
        }

        for (data in dataLists) {
            rule.runOnIdle { dataModel = data }

            // Confirm the children's content
            for (index in 1..8) {
                if (index in data) {
                    rule.onNodeWithText("$index").assertIsDisplayed()
                } else {
                    rule.onNodeWithText("$index").assertIsNotPlaced()
                }
            }
        }
    }

    private val firstItemTag = "firstItemTag"
    private val secondItemTag = "secondItemTag"

    private fun prepareLazyColumnsItemsAlignment(horizontalGravity: Alignment.Horizontal) {
        rule.setContentWithTestViewConfiguration {
            LazyColumn(
                Modifier
                    .testTag(LazyListTag)
                    .requiredWidth(100.dp),
                horizontalAlignment = horizontalGravity
            ) {
                items(listOf(1, 2)) {
                    if (it == 1) {
                        Spacer(
                            Modifier
                                .size(50.dp)
                                .testTag(firstItemTag))
                    } else {
                        Spacer(
                            Modifier
                                .size(70.dp)
                                .testTag(secondItemTag))
                    }
                }
            }
        }

        rule.onNodeWithTag(firstItemTag)
            .assertIsDisplayed()

        rule.onNodeWithTag(secondItemTag)
            .assertIsDisplayed()

        val lazyColumnBounds = rule.onNodeWithTag(LazyListTag)
            .getUnclippedBoundsInRoot()

        with(rule.density) {
            // Verify the width of the column
            assertThat(lazyColumnBounds.left.roundToPx()).isWithin1PixelFrom(0.dp.roundToPx())
            assertThat(lazyColumnBounds.right.roundToPx()).isWithin1PixelFrom(100.dp.roundToPx())
        }
    }

    @Test
    fun lazyColumnAlignmentCenterHorizontally() {
        prepareLazyColumnsItemsAlignment(Alignment.CenterHorizontally)

        rule.onNodeWithTag(firstItemTag)
            .assertPositionInRootIsEqualTo(25.dp, 0.dp)

        rule.onNodeWithTag(secondItemTag)
            .assertPositionInRootIsEqualTo(15.dp, 50.dp)
    }

    @Test
    fun lazyColumnAlignmentStart() {
        prepareLazyColumnsItemsAlignment(Alignment.Start)

        rule.onNodeWithTag(firstItemTag)
            .assertPositionInRootIsEqualTo(0.dp, 0.dp)

        rule.onNodeWithTag(secondItemTag)
            .assertPositionInRootIsEqualTo(0.dp, 50.dp)
    }

    @Test
    fun lazyColumnAlignmentEnd() {
        prepareLazyColumnsItemsAlignment(Alignment.End)

        rule.onNodeWithTag(firstItemTag)
            .assertPositionInRootIsEqualTo(50.dp, 0.dp)

        rule.onNodeWithTag(secondItemTag)
            .assertPositionInRootIsEqualTo(30.dp, 50.dp)
    }

    @Test
    fun flingAnimationStopsOnFingerDown() {
        val items by mutableStateOf((1..20).toList())
        val state = LazyListState()
        rule.setContentWithTestViewConfiguration {
            LazyColumn(
                Modifier
                    .requiredSize(100.dp)
                    .testTag(LazyListTag),
                state = state
            ) {
                items(items) {
                    Spacer(
                        Modifier
                            .requiredSize(20.dp)
                            .testTag("$it"))
                }
            }
        }

        rule.runOnIdle {
            assertThat(state.firstVisibleItemIndex).isEqualTo(0)
            assertThat(state.firstVisibleItemScrollOffset).isEqualTo(0)
        }

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(LazyListTag)
            .performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(100)

        val itemIndexWhenInterrupting = state.firstVisibleItemIndex
        val itemOffsetWhenInterrupting = state.firstVisibleItemScrollOffset

        assertThat(itemIndexWhenInterrupting).isNotEqualTo(0)
        assertThat(itemOffsetWhenInterrupting).isNotEqualTo(0)

        rule.onNodeWithTag(LazyListTag)
            .performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)

        assertThat(state.firstVisibleItemIndex).isEqualTo(itemIndexWhenInterrupting)
        assertThat(state.firstVisibleItemScrollOffset).isEqualTo(itemOffsetWhenInterrupting)
    }

    @Test
    fun removalWithMutableStateListOf() {
        val items = mutableStateListOf("1", "2", "3")

        val itemSize = with(rule.density) { 15.toDp() }

        rule.setContentWithTestViewConfiguration {
            LazyColumn {
                items(items) { item ->
                    Spacer(
                        Modifier
                            .size(itemSize)
                            .testTag(item))
                }
            }
        }

        rule.runOnIdle {
            items.removeLast()
        }

        rule.onNodeWithTag("1")
            .assertIsDisplayed()

        rule.onNodeWithTag("2")
            .assertIsDisplayed()

        rule.onNodeWithTag("3")
            .assertIsNotPlaced()
    }

    @Test
    fun recompositionOrder() {
        val outerState = mutableStateOf(0)
        val innerState = mutableStateOf(0)
        val recompositions = mutableListOf<Pair<Int, Int>>()

        rule.setContent {
            val localOuterState = outerState.value
            LazyColumn {
                items(count = 1) {
                    recompositions.add(localOuterState to innerState.value)
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        rule.runOnIdle {
            innerState.value++
            outerState.value++
        }

        rule.runOnIdle {
            assertThat(recompositions).isEqualTo(
                listOf(0 to 0, 1 to 1)
            )
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun scrolledAwayItemIsNotDisplayedAnymore() {
        lateinit var state: LazyListState
        rule.setContentWithTestViewConfiguration {
            state = rememberLazyListState()
            LazyColumn(
                Modifier
                    .requiredSize(10.dp)
                    .testTag(LazyListTag)
                    .graphicsLayer()
                    .background(Color.Blue),
                state = state
            ) {
                items(2) {
                    val size = if (it == 0) 5.dp else 100.dp
                    val color = if (it == 0) Color.Red else Color.Transparent
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(size)
                            .background(color)
                            .testTag("$it")
                    )
                }
            }
        }

        rule.runOnIdle {
            with(rule.density) {
                runBlocking {
                    // we scroll enough to make the Red item not visible anymore
                    state.scrollBy(6.dp.toPx())
                }
            }
        }

        // and verify there is no Red item displayed
        rule.onNodeWithTag(LazyListTag)
            .captureToImage()
            .assertPixels {
                Color.Blue
            }
    }

    @Test
    fun wrappedNestedLazyRowDisplayCorrectContent() {
        lateinit var state: LazyListState
        rule.setContentWithTestViewConfiguration {
            state = rememberLazyListState()
            LazyColumn(Modifier.size(20.dp), state = state) {
                items(100) {
                    LazyRowWrapped {
                        BasicText("$it", Modifier.size(21.dp))
                    }
                }
            }
        }

        (1..10).forEach { item ->
            rule.runOnIdle {
                runBlocking {
                    state.scrollToItem(item)
                }
            }

            rule.onNodeWithText("$item")
                .assertIsDisplayed()
        }
    }

    @Test
    fun nestedLazyRowChildrenAreReused() {
        lateinit var state: LazyListState
        var remeasuresCount = 0
        val measureModifier = Modifier.layout { _, constraints ->
            if (!isLookingAhead) {
                // Track the post-lookahead measurement count to avoid double counting when
                // lookahead is used.
                remeasuresCount++
            }
            layout(constraints.maxWidth, constraints.maxHeight) {}
        }
        rule.setContentWithTestViewConfiguration {
            state = rememberLazyListState()
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                state = state
            ) {
                items(100) {
                    LazyRow {
                        item {
                            Box(
                                Modifier
                                    .size(25.dp)
                                    .then(measureModifier))
                        }
                    }
                }
            }
        }

        rule.runOnIdle {
            state.prefetchingEnabled = false
            runBlocking {
                state.scrollToItem(1) // now item 0 should be kept for reuse
                assertThat(remeasuresCount).isEqualTo(2)
                remeasuresCount = 0
                state.scrollToItem(2) // item 2 should reuse item 0 slot
            }
        }

        rule.runOnIdle {
            // no remeasures are expected as the LayoutNode should be reused and modifiers
            // didn't change.
            assertThat(remeasuresCount).isEqualTo(0)
        }
    }

    @Test
    fun nestedLazyRowChildrenWithDifferentContentTypeAreReused() {
        lateinit var state: LazyListState
        var remeasuresCount = 0
        val measureModifier = Modifier.layout { _, constraints ->
            if (!isLookingAhead) {
                // Track the post-lookahead measurement count to avoid double counting when
                // lookahead is used.
                remeasuresCount++
            }
            layout(constraints.maxWidth, constraints.maxHeight) {}
        }
        rule.setContentWithTestViewConfiguration {
            state = rememberLazyListState()
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                state = state
            ) {
                items(100) { row ->
                    LazyRow {
                        item(contentType = row) {
                            Box(
                                Modifier
                                    .size(25.dp)
                                    .then(measureModifier))
                        }
                    }
                }
            }
        }

        rule.runOnIdle {
            state.prefetchingEnabled = false
            runBlocking {
                state.scrollToItem(1) // now item 0 should be kept for reuse
                assertThat(remeasuresCount).isEqualTo(2)
                remeasuresCount = 0
                state.scrollToItem(2) // item 2 should reuse item 0 slot
            }
        }

        rule.runOnIdle {
            // no remeasures are expected as the LayoutNode should be reused and modifiers
            // didn't change.
            assertThat(remeasuresCount).isEqualTo(0)
        }
    }

    @Composable
    private fun LazyRowWrapped(content: @Composable () -> Unit) {
        LazyRow {
            items(count = 1) {
                content()
            }
        }
    }

    private fun ComposeContentTestRule.setContentWithTestViewConfiguration(
        composable: @Composable () -> Unit
    ) {
        this.setContent {
            if (useLookaheadScope) {
                LookaheadScope {
                    WithTouchSlop(TestTouchSlop, composable)
                }
            } else {
                WithTouchSlop(TestTouchSlop, composable)
            }
        }
    }
}

internal fun Modifier.drawOutsideOfBounds() = drawBehind {
    val inflate = 20.dp.roundToPx().toFloat()
    drawRect(
        Color.Red,
        Offset(-inflate, -inflate),
        Size(size.width + inflate * 2, size.height + inflate * 2)
    )
}
