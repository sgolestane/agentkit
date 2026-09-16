package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Point data has its own factory, because it has its own shape.
 *
 * <h2>What was wrong with one factory</h2>
 *
 * <p>{@link View#chart(String, List, List)} is categories-and-series: an x axis of named
 * buckets, one value per bucket per series. Bar, line, area and stacked all fit. A scatter does
 * not — its x is a measure, its points do not line up with anything, and two members may share
 * an x or sit anywhere between two of them.
 *
 * <p>Before this, {@code View.chart("scatter", …)} produced a bar chart. That is the right
 * fallback for a word a renderer has never heard of and the wrong answer for one it should
 * know, and the failure is silent: the picture is drawn, it looks like a chart, and it is not
 * a drawing of the data.
 */
class AScatterIsNotACategoricalChartTest {

    private static final List<View.Point> TICKETS = List.of(
            View.Point.of(1, 2, "OPS-1"),
            View.Point.of(5, 9, "OPS-2"),
            View.Point.at(12, 3));

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cloudsOf(View view) {
        return (List<Map<String, Object>>) view.data().get("clouds");
    }

    @Test
    void aScatterCarriesPairsRatherThanCategoriesAndValues() {
        View view = View.scatter("age in days", "comments",
                List.of(View.Cloud.of("open", TICKETS)));

        assertThat(view.kind()).isEqualTo("scatter");
        assertThat(view.data()).containsKeys("xTitle", "yTitle", "clouds");
        // Deliberately NOT the categorical shape. A payload carrying both would be one a
        // renderer could read either way, which is the ambiguity this split exists to remove.
        assertThat(view.data()).doesNotContainKeys("categories", "series", "type");

        Map<String, Object> cloud = cloudsOf(view).getFirst();
        assertThat(cloud).containsEntry("name", "open");
        assertThat((List<?>) cloud.get("points")).hasSize(3);
        assertThat(((List<Map<String, Object>>) cloud.get("points")).getFirst())
                .containsEntry("x", 1).containsEntry("y", 2).containsEntry("label", "OPS-1");
    }

    @Test
    void anUnnamedAxisIsRefusedRatherThanDrawn() {
        // A bar chart's categories say what its x axis is. A cloud of points has nothing that
        // does, so the titles are the only thing making it readable — and a scatter with two
        // unnamed measures is two numbers nobody can name.
        for (String[] axes : List.of(new String[] {null, "comments"},
                new String[] {"age", null}, new String[] {"", "comments"},
                new String[] {"  ", "comments"}, new String[] {"age", ""})) {
            assertThatThrownBy(() -> View.scatter(axes[0], axes[1],
                    List.of(View.Cloud.of("open", TICKETS))))
                    .as("x=%s y=%s", axes[0], axes[1])
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names both of its axes");
        }
    }

    @Test
    void aPointWithHalfACoordinateIsRefusedAtTheFactory() {
        // The opposite of Series, where a null is a gap in a line and means something real —
        // a category with no reading is not a category reading zero. A point is not a gap: a
        // member with no x has no position, and the caller leaves it out of the cloud rather
        // than sending half of one for a renderer to place.
        assertThatThrownBy(() -> View.Point.of(null, 2, "no x"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("x");
        assertThatThrownBy(() -> View.Point.of(1, null, "no y"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("y");
    }

    @Test
    void aScatterCarriesEveryCloudAndLeavesTheCapToTheRenderer() {
        // The colour cap is three, and it lives in the console rather than here: it follows
        // from the palette clearing its colour-vision floors under all-pairs comparison, which
        // is a fact about the drawing and not about the data. A factory that dropped the
        // fourth cloud would lose points a table could still have shown.
        List<View.Cloud> many = List.of(
                View.Cloud.of("a", TICKETS), View.Cloud.of("b", TICKETS),
                View.Cloud.of("c", TICKETS), View.Cloud.of("d", TICKETS),
                View.Cloud.of("e", TICKETS));

        assertThat(cloudsOf(View.scatter("x", "y", many))).hasSize(5);
    }

    @Test
    void aScatterOfNoCloudsIsAViewRatherThanAnException() {
        // A group-by over an empty filter produces this, and it is a result. The console says
        // there are no points; a thrown exception here would fail the tool call instead.
        View empty = View.scatter("age", "comments", List.of());

        assertThat(empty.kind()).isEqualTo("scatter");
        assertThat(cloudsOf(empty)).isEmpty();
    }

    @Test
    void aCloudIsACopyAndCannotBeChangedAfterwards() {
        // The same rule every other View record follows: a caller that keeps its list and
        // mutates it must not change a view that has already been produced.
        List<View.Point> mutable = new java.util.ArrayList<>(TICKETS);
        View.Cloud cloud = View.Cloud.of("open", mutable);
        mutable.clear();

        assertThat(cloud.points()).hasSize(3);
        assertThatThrownBy(() -> cloud.points().add(View.Point.at(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anAxisTitleFromSomebodyElsesWordsIsFenced() {
        // The titles are strings a tool computed, and a tool's strings can come from a
        // ticket's summary. Same treatment as a chart's type, for the same reason.
        View view = View.scatter("age‮reversed", "comments",
                List.of(View.Cloud.of("open", TICKETS)));

        assertThat(String.valueOf(view.data().get("xTitle"))).doesNotContain("‮");
    }
}
