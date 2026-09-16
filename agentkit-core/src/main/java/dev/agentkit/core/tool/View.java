package dev.agentkit.core.tool;

import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Something a tool wants a <em>person</em> to look at, alongside the digest it hands the
 * model.
 *
 * <p>{@link ToolResult#content()} is written for a model: short, prose, cheap in tokens. A
 * group-by over four thousand rows is a good answer and a bad thing to read as pipes, and
 * until this type there was nowhere else for it to go — a tool either flattened its result
 * into markdown and spent the context on it, or dropped the detail.
 *
 * <pre>{@code
 * return ToolResult.ok("142 open tickets across 6 categories")
 *         .withView(View.table(List.of("category", "open"), rows))
 *         .withView(View.chart("bar", categories, List.of(Series.of("open", counts))));
 * }</pre>
 *
 * <h2>A view is not context</h2>
 *
 * <p><strong>The model never sees this.</strong> Only {@code content} is written into the
 * conversation, which is what makes the digest worth writing: a tool can be generous with
 * the person and frugal with the model in the same call, and the two do not trade off. It
 * also means a view is not a channel into the model's context — nothing a view carries can
 * instruct it, because nothing a view carries reaches it. {@code ToolResultCarriesNoViewToTheModelTest}
 * pins that.
 *
 * <p>It is not a security boundary in the other direction. A view is rendered by a client,
 * and cell values in it are as much somebody else's text as the ones in the digest — the
 * result's {@link ToolResult#provenance()} covers both, and a renderer escapes what it
 * draws. What the fence in {@link Spotlight} does for the model, escaping does for the DOM;
 * neither is done by this type.
 *
 * <h2>{@code kind} is open, and the built-in factories are a convenience</h2>
 *
 * <p>A view is a {@code kind} and a JSON-shaped {@code data} map, so a deployment adds a
 * widget by agreeing a kind between a tool and a renderer — {@link #of} — without changing
 * this class or anything in the core. The factories below cover what the framework's own
 * examples needed and carry the shape in one place so a producer and a renderer cannot
 * drift; they are not the vocabulary.
 *
 * <p>Deliberately absent: an {@code approval} kind. A decision needing a person comes from
 * {@link dev.agentkit.core.reliability.PendingApproval}, which is a
 * {@code dev.agentkit.core.reliability} type, and that package already depends on this one.
 * An application builds it with {@link #of}.
 *
 * <h2>What {@code data} may hold, and why the bounds are not advisory</h2>
 *
 * <p>Values are JSON's: {@link String}, {@link Number}, {@link Boolean}, {@code null},
 * {@link List} and {@link Map} with string keys. Anything else is refused at construction
 * rather than coerced, because the alternative fails much later and much worse — a
 * {@code ToolResult} is written into Temporal history and read back on every replay, and a
 * value the converter cannot write fails the workflow <em>task</em>, which Temporal retries
 * indefinitely. The run stalls rather than fails. Refusing here fails the one tool call, in
 * the tool's own thread, with the offending path in the message.
 *
 * <p>The size bounds exist for the same reason and are stated as numbers rather than left
 * to a reviewer: {@link ToolResult#fromThirdParty} records that Jackson refuses above 20 MB
 * on that path, and {@code ToolActivitiesImpl} already cuts a result's <em>content</em> to
 * keep it under. A view is the other half of the same payload and had no bound at all.
 */
public record View(String kind, Map<String, Object> data) {

    /**
     * How many scalars, list elements and map entries one view may carry.
     *
     * <p>Sized against what the thing on the other end is: a widget in a conversation. Ten
     * thousand rows of ten columns is a hundred thousand cells and already past what any
     * table renderer should be handed — the answer there is to aggregate server-side or page,
     * which is what the tools that produce these views do anyway. Chosen well above every
     * view the framework's own examples produce and well below the durable payload limit, so
     * a tool hits it by mistake rather than by working normally.
     */
    public static final int MAX_NODES = 100_000;

    /**
     * How deep {@code data} may nest.
     *
     * <p>Not a size bound — a bound on the recursion that checks the size bound. A
     * self-referential map would otherwise walk until the stack ended, and a stack overflow
     * inside a tool handler is an {@link Error}, which the runners treat as a broken
     * invariant rather than a bad argument. No legitimate view is anywhere near this.
     */
    public static final int MAX_DEPTH = 32;

    /**
     * How many characters of string, over the whole view, keys included.
     *
     * <p>{@link #MAX_NODES} alone does not bound bytes: one string is one node and can be a
     * hundred megabytes. This is the bound that actually keeps a view off the amplifier
     * {@link ToolResult#fromThirdParty} measured, where NFKC expansion turned 200,000
     * characters into 3.6 million.
     */
    public static final int MAX_CHARS = 256_000;

    public View {
        // A kind names a renderer, and it is printed. Held to a name rather than trusted for
        // the reason Spotlight.name gives: an adapter can carry a kind chosen by a far side —
        // an MCP server naming its own widget — and a kind that is not a name is a kind no
        // renderer is registered under anyway. Coerced rather than thrown, because the worst
        // case of an unrenderable kind is a missing widget, and a remote party should not be
        // able to fail a tool call by choosing a bad one.
        kind = Spotlight.name(kind);
        data = copyChecked(data);
    }

    /** A view of {@code kind}, for a widget this framework does not define. */
    public static View of(String kind, Map<String, Object> data) {
        return new View(kind, data);
    }

    // --- the built-in kinds ---------------------------------------------------------

    /**
     * Prose the person should read as markdown — a summary too long for the digest, or the
     * part of an answer that is explanation rather than data.
     */
    public static View markdown(String text) {
        return new View("markdown", Map.of("text", text == null ? "" : text));
    }

    /**
     * Rows under named columns.
     *
     * <p>Column types are the renderer's business — whether a number is right-aligned, how a
     * date reads — and are declared rather than sniffed, because sniffing gets an identifier
     * that happens to be digits wrong. {@link Column#text} when in doubt.
     *
     * @param columns the header, in order; never empty
     * @param rows    one list per row, each the same length as {@code columns}
     */
    public static View table(List<Column> columns, List<? extends List<?>> rows) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("A table view needs at least one column.");
        }
        List<Object> header = new ArrayList<>();
        for (Column column : columns) {
            header.add(Map.of("name", column.name(), "type", column.type()));
        }
        List<Object> body = new ArrayList<>();
        for (List<?> row : rows) {
            // Refused rather than padded. A short row is a producer that has lost track of
            // its own shape, and a renderer that pads renders a lie under the wrong heading.
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("A table view's rows must all have "
                        + columns.size() + " values, one per column; found a row of "
                        + row.size() + ".");
            }
            body.add(row);
        }
        return new View("table", Map.of("columns", header, "rows", body));
    }

    /**
     * A count or a measure per category, as one or more named series.
     *
     * <p>{@code type} is what the renderer should draw — {@code bar}, {@code line},
     * {@code area}, or the deployment's own word. It is a request rather than a contract: a
     * renderer that has no drawing for a type shows the numbers.
     *
     * @param type       the shape to draw
     * @param categories the x axis, in order
     * @param series     one entry per series, each with one value per category
     */
    public static View chart(String type, List<String> categories, List<Series> series) {
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(series, "series");
        List<Object> drawn = new ArrayList<>();
        for (Series one : series) {
            if (one.values().size() != categories.size()) {
                throw new IllegalArgumentException("Series '" + one.name() + "' has "
                        + one.values().size() + " values but there are " + categories.size()
                        + " categories; a chart view cannot line them up.");
            }
            drawn.add(Map.of("name", one.name(), "values", one.values()));
        }
        return new View("chart", Map.of(
                "type", Spotlight.name(type),
                "categories", categories,
                "series", drawn));
    }

    /**
     * Two measures of the same population, one point per member.
     *
     * <h4>A second shape, not a fourth {@code type} on the first</h4>
     *
     * <p>{@link #chart(String, List, List)} is categories-and-series: an x axis of named
     * buckets and one value per bucket per series. Bar, line, area and stacked all fit it and
     * a scatter does not — its x is a <em>measure</em>, its points do not line up with
     * anything, and two points may share an x or have none of their own. Passing
     * {@code "scatter"} to {@code chart} produced a bar chart, which is the correct fallback
     * for a word a renderer does not know and the wrong answer for one it should.
     *
     * <p>So: two factories, because they are two shapes. Guessing an encoding for point data
     * inside a categorical factory is how a chart comes to lie.
     *
     * <h4>Both axes are named, and it refuses if they are not</h4>
     *
     * <p>A scatter with unlabelled axes is two numbers nobody can name. Unlike a bar chart —
     * whose categories say what the x axis is — nothing about a cloud of points carries its
     * own meaning, so the titles are the only thing that makes it readable and they are
     * required rather than optional.
     *
     * @param xTitle what the horizontal measure is; required
     * @param yTitle what the vertical measure is; required
     * @param clouds one entry per named group of points
     */
    public static View scatter(String xTitle, String yTitle, List<Cloud> clouds) {
        Objects.requireNonNull(clouds, "clouds");
        String across = xTitle == null ? "" : xTitle.trim();
        String up = yTitle == null ? "" : yTitle.trim();
        if (across.isEmpty() || up.isEmpty()) {
            throw new IllegalArgumentException("A scatter names both of its axes: x is "
                    + Quoted.of(String.valueOf(xTitle)) + " and y is "
                    + Quoted.of(String.valueOf(yTitle)) + ". Two unnamed measures are two"
                    + " numbers nobody can read — a bar chart's categories say what its x"
                    + " axis is, and a cloud of points has nothing that does.");
        }
        List<Object> drawn = new ArrayList<>();
        for (Cloud cloud : clouds) {
            List<Object> points = new ArrayList<>();
            for (Point point : cloud.points()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("x", point.x());
                one.put("y", point.y());
                one.put("label", point.label() == null ? "" : point.label());
                points.add(one);
            }
            drawn.add(Map.of("name", cloud.name(), "points", points));
        }
        return new View("scatter", Map.of(
                "xTitle", Spotlight.name(across),
                "yTitle", Spotlight.name(up),
                "clouds", drawn));
    }

    /**
     * One headline number, with what it is and — optionally — what it is next to.
     *
     * @param label the measure's name
     * @param value the number or the short string that is the answer
     * @param note  context a reader needs to know whether the number is good; may be null
     */
    public static View stat(String label, Object value, String note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label == null ? "" : label);
        data.put("value", value);
        data.put("note", note == null ? "" : note);
        return new View("stat", data);
    }

    /** A list of small records, each a title and some labelled fields. */
    public static View cards(List<Card> cards) {
        Objects.requireNonNull(cards, "cards");
        List<Object> drawn = new ArrayList<>();
        for (Card card : cards) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("title", card.title());
            one.put("subtitle", card.subtitle());
            one.put("fields", card.fields());
            one.put("url", card.url());
            drawn.add(one);
        }
        return new View("cards", Map.of("cards", drawn));
    }

    /** Two versions of the same text, for a change a person is being asked to read. */
    public static View diff(String title, String before, String after) {
        return new View("diff", Map.of(
                "title", title == null ? "" : title,
                "before", before == null ? "" : before,
                "after", after == null ? "" : after));
    }

    /**
     * What happened, in the order it happened.
     *
     * <p>A run, a workflow, an approval's history. The two questions a person asks of one are
     * "where did it stop" and "what did it do before that", and both are about order — which
     * is why this is not a {@link #table(List, List)}. A table invites sorting, and a timeline
     * sorted by tool name is not a timeline.
     *
     * @param title  what this is a timeline of; may be empty
     * @param moments in order, oldest first
     */
    public static View timeline(String title, List<Moment> moments) {
        Objects.requireNonNull(moments, "moments");
        List<Object> drawn = new ArrayList<>();
        for (Moment moment : moments) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", moment.kind());
            one.put("label", moment.label());
            one.put("detail", moment.detail());
            one.put("at", moment.at());
            one.put("failed", moment.failed());
            drawn.add(one);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", title == null ? "" : title);
        data.put("moments", drawn);
        return new View("timeline", data);
    }

    /**
     * A file the person can open, named by an id the application can serve.
     *
     * <p>The bytes are not here. A view is a payload on a tool result and a result travels
     * through history; a file travels through whatever serves it.
     */
    public static View file(String id, String name, String mediaType, long bytes) {
        return new View("file", Map.of(
                "id", id == null ? "" : id,
                "name", name == null ? "" : name,
                "mediaType", mediaType == null ? "application/octet-stream" : mediaType,
                "bytes", bytes));
    }

    // --- the shapes the factories take ----------------------------------------------

    /**
     * One thing that happened, on a {@link #timeline(String, List)}.
     *
     * @param kind   the family — {@code tool}, {@code model}, {@code approval}; a short word a
     *               renderer sets in small caps, not a sentence
     * @param label  what happened, in a few words
     * @param detail the longer version, which routinely quotes somebody else's text and is
     *               therefore rendered verbatim rather than as markdown
     * @param at     when, already formatted — a timeline is read, not computed with, and a
     *               producer knows the timezone the reader is in better than a renderer does
     * @param failed whether this is where it went wrong, so a reader's eye goes there first
     */
    public record Moment(String kind, String label, String detail, String at, boolean failed) {
        public Moment {
            kind = kind == null ? "" : kind;
            label = label == null ? "" : label;
            detail = detail == null ? "" : detail;
            at = at == null ? "" : at;
        }

        /** The ordinary case: something happened and it was fine. */
        public static Moment of(String kind, String label, String detail) {
            return new Moment(kind, label, detail, "", false);
        }

        /** The one a reader is looking for. */
        public static Moment failure(String kind, String label, String detail) {
            return new Moment(kind, label, detail, "", true);
        }
    }

    /**
     * One column of a {@link #table(List, List)}.
     *
     * @param name what the header says
     * @param type how to render the values: {@code text}, {@code number}, {@code date} or
     *             {@code bool}
     */
    public record Column(String name, String type) {
        public Column {
            Objects.requireNonNull(name, "name");
            type = type == null ? "text" : type;
        }

        public static Column text(String name) {
            return new Column(name, "text");
        }

        /**
         * Several {@link #text} columns at once, for the common table whose columns are all
         * strings: {@code View.table(Column.texts("category", "open"), rows)}.
         *
         * <p>Here rather than as a {@code table(List<String>, ...)} overload because that one
         * cannot exist — it erases to the same signature as
         * {@link View#table(List, List)} — and the array-typed workaround it forced read
         * worse than this at every call site.
         */
        public static List<Column> texts(String... names) {
            List<Column> columns = new ArrayList<>();
            for (String name : names) {
                columns.add(text(name));
            }
            return columns;
        }

        public static Column number(String name) {
            return new Column(name, "number");
        }

        public static Column date(String name) {
            return new Column(name, "date");
        }

        public static Column bool(String name) {
            return new Column(name, "bool");
        }
    }

    /**
     * One named series of a {@link #chart(String, List, List)}.
     *
     * <p>A {@code null} value is a gap in the series and is carried as one — a category with
     * no reading is not the same as a category reading zero, and a renderer that cannot tell
     * them apart draws a line through a hole. {@code List.copyOf} was the first spelling here
     * and refuses nulls, which made the gap unexpressible while {@link #finite} was telling
     * callers to "send null for a missing number".
     */
    public record Series(String name, List<Number> values) {
        public Series {
            Objects.requireNonNull(name, "name");
            values = java.util.Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(values, "values")));
        }

        public static Series of(String name, List<Number> values) {
            return new Series(name, values);
        }
    }

    /**
     * One member of the population a {@link #scatter} draws.
     *
     * <p>Both coordinates are required and neither may be null. That is the opposite of
     * {@link Series}, where a null is a gap in a line and carries real meaning — a category
     * with no reading is not a category reading zero. A point is not a gap: a member with no
     * x has no position, and the honest thing is for the caller to leave it out of the cloud
     * rather than to send half of one and let a renderer decide where to put it.
     *
     * @param label what this point is, for a tooltip; may be null for an unnamed member
     */
    public record Point(Number x, Number y, String label) {
        public Point {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }

        public static Point of(Number x, Number y, String label) {
            return new Point(x, y, label);
        }

        public static Point at(Number x, Number y) {
            return new Point(x, y, null);
        }
    }

    /**
     * One named group of points in a {@link #scatter}.
     *
     * <p>Named a cloud rather than a series because it is not one: a series has an order and
     * one value per category, and these have neither.
     */
    public record Cloud(String name, List<Point> points) {
        public Cloud {
            Objects.requireNonNull(name, "name");
            points = List.copyOf(Objects.requireNonNull(points, "points"));
        }

        public static Cloud of(String name, List<Point> points) {
            return new Cloud(name, points);
        }
    }

    /** One entry of a {@link #cards(List)}. */
    public record Card(String title, String subtitle, Map<String, Object> fields, String url) {
        public Card {
            title = title == null ? "" : title;
            subtitle = subtitle == null ? "" : subtitle;
            fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
            url = url == null ? "" : url;
        }

        public static Card of(String title, Map<String, Object> fields) {
            return new Card(title, "", fields, "");
        }
    }

    // --- the check ------------------------------------------------------------------

    /**
     * A deep, immutable copy of {@code data} whose every value is JSON-shaped and whose size
     * is within the bounds above.
     *
     * <p>Copied rather than wrapped because the caller keeps a reference to what it passed:
     * a tool that builds a map, hands it over and then keeps filling it would otherwise
     * mutate a result already recorded. Records are values here, and this is what makes this
     * one actually be one.
     *
     * <p><strong>This is the only copy, and the factories above deliberately do not repeat
     * it.</strong> They did at first — {@code table} wrapped each row in a fresh
     * {@code ArrayList}, {@code chart} its categories, {@code cards} its fields — and every
     * one of those was dead: this runs over the whole map afterwards and rebuilds it
     * regardless. Measured by planting the reverse, which is how they were found: dropping
     * the copy in {@code table} left {@code theCallerKeepsNoHandleOnWhatTheViewNowHolds}
     * green, because the test was measuring this method the whole time. Two defences where
     * one is load-bearing means a later reader cannot tell which one is.
     */
    private static Map<String, Object> copyChecked(Map<String, Object> data) {
        if (data == null) {
            // Coerced, not required, for the reason ToolResult's own constructor states: a
            // payload written before this component existed deserializes with null here, and
            // on the durable path that payload belongs to a run already in flight.
            return Map.of();
        }
        Budget budget = new Budget();
        Object copy = copyValue(data, budget, 0, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> checked = (Map<String, Object>) copy;
        return checked;
    }

    /** Running totals, so the bounds are on the whole view rather than on each branch. */
    private static final class Budget {
        private int nodes;
        private int chars;
    }

    private static Object copyValue(Object value, Budget budget, int depth, String path) {
        if (depth > MAX_DEPTH) {
            throw refuse("nests deeper than " + MAX_DEPTH + " levels", path);
        }
        if (++budget.nodes > MAX_NODES) {
            throw refuse("carries more than " + MAX_NODES + " values. Aggregate it or page it"
                    + " — this is a widget in a conversation, not an export", path);
        }
        return switch (value) {
            case null -> null;
            case String text -> {
                budget.chars += text.length();
                if (budget.chars > MAX_CHARS) {
                    throw refuse("carries more than " + MAX_CHARS + " characters of text", path);
                }
                yield text;
            }
            // Boxed primitives and BigDecimal write as JSON numbers; a Number subclass that
            // does not is the caller's to keep out, and it would be refused by the default
            // arm rather than silently written as its toString.
            case Integer i -> i;
            case Long l -> l;
            case Double d -> finite(d, path);
            case Float f -> {
                // Checked as a double and yielded as a Float: widening it here would write
                // 0.1f into the payload as 0.10000000149011612, which is a different number
                // on the screen than the one the tool computed.
                finite(f.doubleValue(), path);
                yield f;
            }
            case Short s -> s;
            case Byte b -> b;
            case java.math.BigInteger big -> big;
            case java.math.BigDecimal big -> big;
            case Boolean bool -> bool;
            case Map<?, ?> map -> copyMap(map, budget, depth, path);
            case List<?> list -> copyList(list, budget, depth, path);
            default -> throw new IllegalArgumentException("A view may only carry JSON values"
                    + " — strings, numbers, booleans, null, lists and string-keyed maps — and"
                    + " " + at(path) + " is a " + value.getClass().getName() + ". Convert it"
                    + " at the tool, where the right rendering is known; a value the JSON"
                    + " converter cannot write stalls a durable run rather than failing it.");
        };
    }

    /**
     * {@code d} unless it is a value JSON has no spelling for.
     *
     * <p>{@code NaN} and the infinities are legal doubles and illegal JSON. Jackson writes
     * them as bare {@code NaN}, which most parsers — including the browser's — reject, so a
     * single division by zero in a tool would produce a view no client could read and, on the
     * durable path, a payload that fails to parse on replay.
     */
    private static double finite(double d, String path) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw refuse("holds " + d + ", which JSON cannot spell. Send null for a missing"
                    + " number, or a string for one that is genuinely infinite", path);
        }
        return d;
    }

    private static Map<String, Object> copyMap(Map<?, ?> map, Budget budget, int depth,
            String path) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("A view's maps must have string keys, and "
                        + at(path) + " has a key of type "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName())
                        + ".");
            }
            budget.chars += key.length();
            if (budget.chars > MAX_CHARS) {
                throw refuse("carries more than " + MAX_CHARS + " characters of text", path);
            }
            copy.put(key, copyValue(entry.getValue(), budget, depth + 1,
                    path.isEmpty() ? key : path + '.' + key));
        }
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyList(List<?> list, Budget budget, int depth, String path) {
        List<Object> copy = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            copy.add(copyValue(list.get(i), budget, depth + 1, path + '[' + i + ']'));
        }
        return java.util.Collections.unmodifiableList(copy);
    }

    private static IllegalArgumentException refuse(String what, String path) {
        return new IllegalArgumentException("A view " + what + " (" + at(path) + ").");
    }

    /** Where in the view, for a message a developer reads rather than a model. */
    private static String at(String path) {
        return path.isEmpty() ? "the view itself"
                : "'" + Quoted.of(Cut.to(path, 200)) + "'";
    }
}
