package dev.agentkit.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a string may not do to the line it is printed into (#98).
 *
 * <p>The reader here is an operator rather than a model, which is the only thing separating
 * this from {@link OneLine}. Both exist because text chosen by somebody else becomes
 * structure when it is interpolated into a listing.
 */
class QuotedTest {

    /** What a backend that goes through printStackTrace actually writes. */
    private static String rendered(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    @Test
    @DisplayName("a name cannot write a second log entry")
    void cannotForgeALine() {
        // The defect #98 names: a planted filename reaches FileMemoryStore's warning, and a
        // line terminator in it writes an entry of its own in the framework's voice.
        String forged = "a.md\n2026-08-21 WARN  d.a.c.memory.FileMemoryStore - nothing is wrong";

        String quoted = Quoted.of(forged);

        assertThat(quoted).doesNotContain("\n").contains("\\u000A");
        assertThat(quoted.lines()).hasSize(1);
        // Every terminator, not only the one \n: U+2028 and U+2029 end a line for most
        // renderers and are not ISO controls.
        for (String terminator : List.of("\r", " ", " ", "", "\f", "")) {
            assertThat(Quoted.of("a" + terminator + "b").lines())
                    .as("%04X ended a line", (int) terminator.charAt(0))
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("a name cannot move the cursor of the terminal reading the log")
    void cannotDriveATerminal() {
        // Logs are read in terminals. U+001B starts a sequence that recolours, erases the
        // line, or scrolls away what is above it — and a filename is a fine place to keep
        // one. This is the half OneLine's collapsing would not have answered either.
        String ansi = "innocent.md[2K[1;31mDELETED EVERYTHING";

        assertThat(Quoted.of(ansi))
                .doesNotContain("")
                .contains("\\u001B");
    }

    @Test
    @DisplayName("two different names cannot render as one")
    void cannotHideTheDifference() {
        // A bidi override or a zero-width joiner makes distinct strings render identically,
        // so a warning about one file reads as a warning about another.
        assertThat(Quoted.of("a‮b")).isNotEqualTo(Quoted.of("ab"));
        assertThat(Quoted.of("a‍b")).isNotEqualTo(Quoted.of("ab"));
        assertThat(Quoted.of("a﻿b")).isNotEqualTo(Quoted.of("ab"));
        // And an unpaired surrogate is not a character at all.
        assertThat(Quoted.of("a\uD800")).contains("\\uD800");
    }

    @Test
    @DisplayName("ordinary text is left alone, so a log stays readable")
    void leavesOrdinaryTextAlone() {
        // The cost of escaping too widely is a log nobody wants to read, so the rule has to
        // stop at what can restructure a line.
        for (String ordinary : List.of("facts/user.md", "café.md", "notes/2026-08-21.md",
                "ops team", "a b — c", "日本語.md", "😀.md", "50%.md", "a'b\"c")) {
            assertThat(Quoted.of(ordinary))
                    .as("escaped something harmless: %s", ordinary)
                    .isEqualTo(ordinary);
        }
    }

    @Test
    @DisplayName("null is text, because this is called on the failure path")
    void survivesNull() {
        assertThat(Quoted.of(null)).isEqualTo("null");
        assertThat(Quoted.each(null)).isEqualTo("[]");
        assertThat(Quoted.each(List.of())).isEqualTo("[]");
    }

    @Test
    @DisplayName("an element cannot forge a second entry in the batch")
    void quotesEachItemOfABatch() {
        // The first version handed back a List and left the rendering to
        // AbstractCollection.toString — so the line was safe and the list printed inside it
        // was not. A file named "innocent.md, secrets.md" rendered exactly as two ignored
        // files, and a ']' closed the list early. That is #98 one level up, in the helper
        // written for #98.
        assertThat(Quoted.each(List.of("ok.md", "bad\nmd")))
                .isEqualTo("['ok.md', 'bad\\u000Amd']");
        assertThat(Quoted.each(List.of("innocent.md, secrets.md")))
                .as("one name rendered as two entries")
                .isNotEqualTo(Quoted.each(List.of("innocent.md", "secrets.md")));
        assertThat(Quoted.each(List.of("a].md"))).isEqualTo("['a].md']");
        // An element cannot end its own entry either.
        assertThat(Quoted.each(List.of("a'.md"))).isEqualTo("['a\\u0027.md']");
        assertThat(Quoted.each(List.of("bad\nmd"))).doesNotContain("\n");
    }

    @Test
    @DisplayName("a batch the operator will not read is capped, and says how many are left")
    void capsABatchThatNobodyWouldRead() {
        // One entry per non-key file in a directory anything may write to. Five thousand of
        // them made a 94,000-character warning, which is not read by the operator it is for.
        List<String> many = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(i -> "file" + i + ".md").toList();

        String rendered = Quoted.each(many);

        assertThat(rendered).hasSizeLessThan(500).endsWith("and 4980 more]");
        assertThat(Quoted.each(many, 2)).isEqualTo("['file0.md', 'file1.md', and 4998 more]");
    }

    @Test
    @DisplayName("the escaping is a trade, and the trade is visible")
    void escapesWhatSomeScriptsLegitimatelyNeed() {
        // Wide and lossless is the right call for a name chosen by somebody else, but it is
        // a trade rather than a definition, and the first version's examples were exactly
        // the set that passes. These do not, and that is worth knowing on purpose.
        assertThat(Quoted.of("می\u200Cخواهم.md")).contains("\\u200C");   // Persian ZWNJ
        assertThat(Quoted.of("👨\u200D👩\u200D👧.md")).contains("\\u200D");  // ZWJ emoji family
        assertThat(Quoted.of("Bundes\u00ADliga.md")).contains("\\u00AD"); // soft hyphen
        // And what it does not catch, so nobody reads the category as the rule: U+3164 is a
        // letter that renders blank.
        assertThat(Quoted.restructures(0x3164)).isFalse();
    }

    @Test
    @DisplayName("what was escaped can be told from what was written")
    void isInjective() {
        // The class promises an operator "knows what was actually there", and that promise
        // is only worth something if the escaping is reversible. It was not: '\\' was never
        // escaped, so a file named with the six literal characters of an escape sequence
        // quoted to output byte-identical to one holding a real escape character. An
        // operator un-escaping the name to see what it was would manufacture a live ANSI
        // sequence out of a name that
        // never had one, and a detection rule grepping for the escape spelling would fire
        // on the wrong one of the two.
        String literal = "b.md\\u001B[2Kgone";               // no escape character anywhere
        String real = "b.md\u001B[2Kgone";                    // a real U+001B
        assertThat(Quoted.of(literal)).isNotEqualTo(Quoted.of(real));
        assertThat(Quoted.of(literal)).isEqualTo("b.md\\\\u001B[2Kgone");
        assertThat(Quoted.of(real)).isEqualTo("b.md\\u001B[2Kgone");

        // Injective over every code point in a fixed position, which is the general claim
        // and not a sample of it.
        java.util.Set<String> quoted = new java.util.HashSet<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (!quoted.add(Quoted.of("a" + new String(Character.toChars(cp)) + "b"))) {
                org.junit.jupiter.api.Assertions.fail("two code points quoted alike at U+"
                        + Integer.toHexString(cp));
            }
        }
    }

    @Test
    @DisplayName("un-escaping recovers exactly what was there")
    void roundTrips() {
        // Distinctness is not reversibility, and the javadoc promises the second: an
        // operator or a log viewer un-escapes a name to see what it was. Asserted with a
        // decoder written here rather than by calling the encoder backwards, because a
        // decoder written against the *documented grammar* is what a reader would actually
        // apply, and it is the thing that breaks if someone adds a shorthand like \\n.
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp += 7) {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                continue;   // cannot be put in a String except as the lone char below
            }
            String original = "a" + new String(Character.toChars(cp)) + "\\b";
            assertThat(unescape(Quoted.of(original)))
                    .as("U+%04X did not round trip", cp)
                    .isEqualTo(original);
        }
        for (char lone = Character.MIN_SURROGATE; lone <= Character.MAX_SURROGATE; lone++) {
            String original = "a" + lone + "b";
            assertThat(unescape(Quoted.of(original)))
                    .as("the lone surrogate U+%04X did not round trip", (int) lone)
                    .isEqualTo(original);
        }
    }

    /** The grammar {@link Quoted#of} documents, read the way a log viewer would read it. */
    private static String unescape(String quoted) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c != '\\') {
                out.append(c);
            } else if (quoted.charAt(i + 1) == '\\') {
                out.append('\\');
                i++;
            } else if (quoted.charAt(i + 1) == 'u') {
                out.append((char) Integer.parseInt(quoted, i + 2, i + 6, 16));
                i += 5;
            } else {
                out.appendCodePoint(Integer.parseInt(quoted, i + 2, i + 10, 16));
                i += 9;
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("a code point above the basic plane gets an escape that means it")
    void escapesAboveTheBasicPlaneWithoutAmbiguity() {
        // A four-digit format does not truncate a wider code point — it *widens*, so
        // U+E0001 came out
        // as the seven characters backslash-uE0001, which any reader of a four-digit escape
        // takes as U+E000 followed
        // by a '1'. Every astral formatting character is affected: the tags block, the
        // musical and Brahmi controls. \U with eight digits is unambiguous and is what
        // Python and ICU spell it as.
        assertThat(Quoted.restructures(0xE0001)).isTrue();
        assertThat(Quoted.of(new String(Character.toChars(0xE0001))))
                .isEqualTo("\\U000E0001");
        assertThat(Quoted.of(new String(Character.toChars(0xE0041))))
                .isEqualTo("\\U000E0041");
        // And the ordinary supplementary characters are still untouched.
        assertThat(Quoted.of("😀.md")).isEqualTo("😀.md");
    }

    @Test
    @DisplayName("a throwable's message is escaped too, because the layout prints it")
    void escapesTheMessageOfALoggedFailure() {
        // The trailing Throwable is not a '{}' argument — the logging framework renders it
        // itself, and escaping the other arguments does not touch it. That is a live
        // channel: a remote HTTP error body reaches an LlmException, and a filesystem
        // exception carries the name of whatever file was planted.
        Throwable raw = new java.io.IOException(
                "a.md\n2026-08-21 WARN  d.a.c.Ops - disk healthy\u001B[1;32m");

        Throwable safe = Quoted.failure(raw);

        assertThat(safe.getMessage()).doesNotContain("\n").doesNotContain("\u001B");
        // The class name is in the *message*, not only in toString, and that is the fix
        // rather than a detail. Backends disagree about which they render: slf4j-simple and
        // log4j2's %throwable go through printStackTrace, which uses toString, while
        // logback builds its header from getClass().getName() and never calls toString at
        // all — so a wrapper announcing its type only through toString lost every exception
        // class under the more common of the two.
        assertThat(safe.getMessage()).startsWith("java.io.IOException: ").contains("\\u000A");
        assertThat(safe.toString()).isEqualTo(safe.getMessage());
        assertThat(safe.toString().lines()).hasSize(1);
        assertThat(renderedByAnyBackend(safe))
                .as("a backend that renders class-plus-message lost the original type")
                .contains("java.io.IOException")
                .doesNotContain("\u001B");
        // As does the stack trace, which is the whole reason a throwable is passed at all.
        assertThat(safe.getStackTrace()).isEqualTo(raw.getStackTrace());
        assertThat(Quoted.failure(null)).isNull();
    }

    @Test
    @DisplayName("a cause is escaped as well, and a cycle in one does not hang")
    void escapesTheWholeChain() {
        Throwable inner = new IllegalStateException("inner\nforged");
        Throwable safe = Quoted.failure(new RuntimeException("outer\nforged", inner));

        assertThat(safe.getMessage()).isEqualTo("java.lang.RuntimeException: outer\\u000Aforged");
        assertThat(safe.getCause().toString())
                .isEqualTo("java.lang.IllegalStateException: inner\\u000Aforged");

        // A cause chain can be circular — printStackTrace has a guard for exactly that —
        // and this runs on the failure path, where recursing until the stack goes is the
        // worst possible way to report a problem.
        Throwable a = new RuntimeException("a");
        Throwable b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(depthOf(Quoted.failure(a))).isLessThan(30);

        // And where it stops it says so, rather than simply ending. A chain that just stops
        // reads as a chain that ended, and the root cause is the end an operator came for —
        // the argument Cut makes for a model, one reader over.
        Throwable deep = new RuntimeException("root-cause-that-matters");
        for (int i = 0; i < 25; i++) {
            deep = new RuntimeException("layer" + i, deep);
        }
        Throwable walked = Quoted.failure(deep);
        Throwable last = walked;
        while (last.getCause() != null) {
            last = last.getCause();
        }
        assertThat(last.getMessage()).contains("more cause(s), not walked");
    }

    @Test
    @DisplayName("the helper cannot turn one failure into a worse one")
    void survivesAThrowableThatCannotBeRead() {
        // getMessage and getStackTrace are overridable, and several call sites are catch
        // blocks written expressly to be un-abortable — "a thrown tool must not abort the
        // run", "a failing tick must not cancel the timer, silently and for good". A
        // logging helper that throws on the failure path replaces the failure being
        // reported with a different, fatal one. This is the trade Quoted.of already makes
        // for null, on a worse path.
        Throwable unreadable = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("boom");
            }
        };

        Throwable safe = Quoted.failure(unreadable);

        assertThat(safe).isNotNull();
        assertThat(safe.getMessage()).contains("could not be read").contains("boom");
    }

    @Test
    @DisplayName("a message a tool chose the length of does not choose the log's")
    void boundsWhatOneFailureWritesToTheLog() {
        // The depth cap bounded the shape of a chain and not its size, so the injection half
        // of "a hostile throwable on a log line" was closed and the volume half was open.
        // Measured through slf4j-simple on Agent.runTool's line: 200,000 characters wrote
        // 200,160 bytes, 1,000,000 wrote 1,000,160, and a CI run whose tests threw dozens of
        // them took 25+ minutes against a 3.5 minute norm.
        int header = "java.lang.IllegalStateException: ".length();

        String ordinary = Quoted.failure(
                new IllegalStateException("Connection refused: api.example.com:443")).getMessage();
        String vast = Quoted.failure(new IllegalStateException("X".repeat(5_000_000))).getMessage();

        // Nothing happens to a message anybody actually writes.
        assertThat(ordinary).isEqualTo(
                "java.lang.IllegalStateException: Connection refused: api.example.com:443");
        // And five million characters cost the same as four thousand and one.
        assertThat(vast.length()).isLessThan(header + 4_100);
        assertThat(vast).contains(Cut.MARKER);
    }

    @Test
    @DisplayName("the window is the one the model gets, so the two records agree")
    void carriesAsMuchOfTheMessageAsTheModelIsShown() {
        // ToolResult.MAX_FAILURE_CHARS shows the model 4,000 characters of the same message.
        // An operator asking "what did the model read" must not be answered by a line
        // carrying less of it than the transcript does.
        String atTheWindow = "X".repeat(4_000);

        String kept = Quoted.failure(new IllegalStateException(atTheWindow)).getMessage();

        assertThat(kept).isEqualTo("java.lang.IllegalStateException: " + atTheWindow);
        assertThat(kept).doesNotContain(Cut.MARKER).doesNotContain("[message cut]");
    }

    @Test
    @DisplayName("the escape expands, so the escaped result is bounded as well")
    void boundsTheEscapedResultAndNotOnlyTheMessage() {
        // Quoted.of never shrinks and expands six-fold on a newline or a zero-width
        // formatting character, so a message inside the first ceiling can still be six times
        // it once written down safely: 200,000 newlines wrote 1,200,160 bytes.
        String allNewlines = "\n".repeat(200_000);

        String written = Quoted.failure(new IllegalStateException(allNewlines)).getMessage();

        assertThat(written.length()).isLessThan(8_200);
        assertThat(written).contains("\\u000A").doesNotContain("\n");
        // A message inside BOTH ceilings is not cut: 800 newlines escape to 4,800.
        String inside = Quoted.failure(
                new IllegalStateException("\n".repeat(800))).getMessage();
        assertThat(inside).doesNotContain("[message cut]").doesNotContain(Cut.MARKER);
        assertThat(inside.length()).isGreaterThan(4_800);
    }

    @Test
    @DisplayName("a cut is said where a message cannot reach")
    void declaresTheCutBeforeTheColon() {
        // Cut.MARKER is inside the message and Cut says of itself that it "is a hint to a
        // reader, not evidence about the writer" — any message can contain it. The class
        // name is the one span here a message cannot reach, so the note goes there.
        String cut = Quoted.failure(new IllegalStateException("X".repeat(4_001))).getMessage();
        String forging = Quoted.failure(
                new IllegalStateException("[message cut]: nothing was cut")).getMessage();

        assertThat(cut).startsWith("java.lang.IllegalStateException [message cut]: ");
        // The forgery lands after the colon, which is the whole of what the position buys.
        assertThat(forging).startsWith("java.lang.IllegalStateException: [message cut]");
        assertThat(forging.indexOf("[message cut]"))
                .isGreaterThan(forging.indexOf(": "));
    }

    @Test
    @DisplayName("a message inside the first ceiling can still be cut by the second")
    void declaresACutTheEscapeAloneCaused() {
        // The witness for the outer half of the OR, which nothing else reaches: 4,000
        // newlines pass MAX_MESSAGE_CHARS untouched and escape to 24,000. Only the second
        // ceiling fires, so a note derived from the first alone would call this complete.
        String insideTheFirstCeiling = "\n".repeat(4_000);

        String written = Quoted.failure(
                new IllegalStateException(insideTheFirstCeiling)).getMessage();

        assertThat(written).startsWith("java.lang.IllegalStateException [message cut]: ");
        assertThat(written.length()).isLessThan(8_200);
    }

    @Test
    @DisplayName("the cut is carried, not recovered from a length")
    void reportsACutWhoseOutputIsAsLongAsItsInput() {
        // #155's case, at this seam: a message of exactly the ceiling plus Cut.MARKER comes
        // back the length it went in, so an equality test on lengths calls it complete.
        String exactly = "X".repeat(4_000 + Cut.MARKER.length());

        String written = Quoted.failure(new IllegalStateException(exactly)).getMessage();

        assertThat(written).contains("[message cut]");
        assertThat(written.length())
                .isEqualTo("java.lang.IllegalStateException [message cut]: ".length()
                        + exactly.length());
    }

    @Test
    @DisplayName("every message in the chain is bounded, not only the outermost")
    void boundsEveryNodeOfTheChain() {
        Throwable deep = new IllegalStateException("Y".repeat(500_000));
        for (int i = 0; i < 8; i++) {
            deep = new RuntimeException("X".repeat(500_000), deep);
        }

        Throwable safe = Quoted.failure(deep);

        int total = 0;
        for (Throwable t = safe; t != null; t = t.getCause()) {
            assertThat(t.getMessage()).contains("[message cut]");
            total += t.getMessage().length();
        }
        // Nine nodes that carried 4,500,000 characters between them.
        assertThat(total).isLessThan(9 * 4_200);
    }

    @Test
    @DisplayName("a throwable that cannot be read cannot write megabytes either")
    void boundsTheFallbackMessageToo() {
        // The branch for a throwable whose own getMessage throws escaped that failure's
        // message unbounded, which is the same hole one catch block over.
        Throwable unreadable = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("Z".repeat(2_000_000));
            }
        };

        String safe = Quoted.failure(unreadable).getMessage();

        assertThat(safe).contains("could not be read");
        assertThat(safe.length()).isLessThan(4_200 + 200);
    }

    @Test
    @DisplayName("a suppressed list a caller chose the length of does not choose the log's")
    void boundsHowWideASuppressedListCanBe() {
        // The reachable path, not a hypothetical one: Observations.ran exists because an
        // AgentObserver is somebody else's code, and its default handler is LOGGING, which
        // calls Quoted.failure. One try-with-resources in a loop over a large collection of
        // closeables hands back exactly this. Measured through that handler: 139 bytes an
        // entry, 14,289,153 bytes and 338 ms at a hundred thousand.
        Throwable vast = new IllegalStateException("the body threw");
        for (int i = 0; i < 100_000; i++) {
            vast.addSuppressed(new java.io.IOException("close failed on resource " + i));
        }
        Throwable atTheCap = new IllegalStateException("the body threw");
        for (int i = 0; i < 16; i++) {
            atTheCap.addSuppressed(new java.io.IOException("close failed on resource " + i));
        }

        Throwable safe = Quoted.failure(vast);

        // Sixteen entries plus the tail that says what is missing.
        assertThat(safe.getSuppressed()).hasSize(17);
        // Measured against the cap rather than against a constant, because a rendered trace
        // carries the frames of whoever built it and this test's stack is not the agent
        // loop's. What matters is that the count stops mattering: a hundred thousand costs
        // what sixteen costs, plus one sentence.
        assertThat(rendered(safe).length())
                .isLessThan(rendered(Quoted.failure(atTheCap)).length() + 200);
    }

    @Test
    @DisplayName("what it did not walk is counted where the entries would have been")
    void saysHowManySuppressedExceptionsItDidNotWalk() {
        // Dropping the tail is [message cut]'s defect one field over: a reader cannot see a
        // suppressed exception that is not there, and a list that simply stops reads as a
        // list that ended. Same shape as the depth cap's own marker.
        Throwable body = new IllegalStateException("the body threw");
        for (int i = 0; i < 100; i++) {
            body.addSuppressed(new java.io.IOException("close failed on resource " + i));
        }

        Throwable[] suppressed = Quoted.failure(body).getSuppressed();

        assertThat(suppressed[suppressed.length - 1].toString())
                .isEqualTo("[suppressed list cut]: 84 more suppressed exception(s), not walked");
    }

    @Test
    @DisplayName("a suppressed list the depth cap stops at is announced, not dropped")
    void announcesASuppressedListTheDepthCapCouldNotReach() {
        // The walk used to skip a node's whole suppressed list once the depth cap was
        // reached, in silence. Measured on this shape before: five layers of twenty-one lost
        // theirs with nothing saying so, and the only "not walked" line in the render was
        // the cause chain's.
        Throwable node = new IllegalStateException("root");
        node.addSuppressed(new java.io.IOException("root resource"));
        for (int i = 0; i < 20; i++) {
            Throwable outer = new RuntimeException("layer " + i, node);
            outer.addSuppressed(new java.io.IOException("layer " + i + " resource"));
            node = outer;
        }

        String written = rendered(Quoted.failure(node));

        assertThat(written).contains("suppressed exception(s), not walked");
    }

    @Test
    @DisplayName("one throwable added a hundred thousand times is not printed that often")
    void boundsTheListEvenWhenEveryEntryIsTheSameObject() {
        // addSuppressed does not deduplicate, so the identity map keeps a repeat from being
        // walked twice and does not keep it from being printed. Measured before: one
        // allocated throwable, added a hundred thousand times, wrote 5,800,330 bytes of
        // "already reported above".
        Throwable body = new IllegalStateException("the body threw");
        Throwable shared = new java.io.IOException("close failed");
        for (int i = 0; i < 100_000; i++) {
            body.addSuppressed(shared);
        }
        Throwable once = new IllegalStateException("the body threw");
        once.addSuppressed(new java.io.IOException("close failed"));

        Throwable safe = Quoted.failure(body);

        assertThat(safe.getSuppressed()).hasSize(17);
        // Fifteen "already reported above" entries and one tail, against the same body
        // carrying a single real one: the repeats cost less than the entry they repeat.
        assertThat(rendered(safe).length())
                .isLessThan(2 * rendered(Quoted.failure(once)).length());
    }

    @Test
    @DisplayName("an ordinary suppressed list is untouched")
    void leavesARealSuppressedListAlone() {
        // A try-with-resources contributes one suppressed exception per resource it fails to
        // close, and all 27 try-with-resources statements in this repository declare exactly
        // one resource. Nothing realistic comes near the cap.
        Throwable body = new IllegalStateException("the body threw");
        body.addSuppressed(new java.io.IOException("close failed"));

        Throwable[] suppressed = Quoted.failure(body).getSuppressed();

        assertThat(suppressed).hasSize(1);
        assertThat(suppressed[0].toString()).isEqualTo("java.io.IOException: close failed");
    }

    @Test
    @DisplayName("a chain that shares nodes with its suppressed list is walked once each")
    void doesNotExplodeOnAThrowableGraph() {
        // A cause chain and a suppressed list that share nodes make a graph, not a tree,
        // and re-wrapping per path rather than per node turned nineteen objects into
        // sixty-five thousand wrappers and a hundred megabytes. printStackTrace tracks
        // what it has already printed by identity for exactly this reason; so does this.
        Throwable node = new RuntimeException("leaf");
        for (int i = 0; i < 18; i++) {
            Throwable next = new RuntimeException("level" + i, node);
            next.addSuppressed(node);
            node = next;
        }

        long before = System.nanoTime();
        Throwable safe = Quoted.failure(node);
        long millis = (System.nanoTime() - before) / 1_000_000;

        assertThat(safe).isNotNull();
        assertThat(millis).as("walking nineteen objects took %d ms", millis).isLessThan(500);
        assertThat(countNodes(safe, 0))
                .as("a shared node was wrapped once per path rather than once")
                .isLessThan(200);
        // And the repeat says what it is rather than being dropped.
        assertThat(safe.getSuppressed()[0].toString()).contains("circular reference");
    }

    private static int countNodes(Throwable t, int depth) {
        if (t == null || depth > 40) {
            return 0;
        }
        int total = 1 + countNodes(t.getCause(), depth + 1);
        for (Throwable suppressed : t.getSuppressed()) {
            total += countNodes(suppressed, depth + 1);
        }
        return total;
    }

    private static int depthOf(Throwable t) {
        int depth = 0;
        for (Throwable at = t; at != null && depth < 100; at = at.getCause()) {
            depth++;
        }
        return depth;
    }

    /** What a backend that prints {@code getClass().getName() + ": " + getMessage()} shows. */
    private static String renderedByAnyBackend(Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    @Test
    @DisplayName("escaping is not paid for a line that is never printed")
    void defersUntilSomethingRendersIt() {
        // An argument to log.info is evaluated whether or not the level is on, and this
        // input is model-written and unbounded. Measured at a disabled level, escaping half
        // a megabyte of hostile text cost four orders of magnitude more than passing the
        // raw string did before it was escaped at all.
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        Object deferred = Quoted.lazily(() -> {
            calls.incrementAndGet();
            return Quoted.of("a\nb");
        });

        assertThat(deferred.toString()).isEqualTo("a\\u000Ab");
        assertThat(calls).hasValue(1);

        // The claim worth pinning is not that the supplier is unread before anything reads
        // it — it could not be — but that the *logger* does not read it at a level that is
        // off. Asserted through slf4j rather than about it.
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("quoted-test-off");
        org.junit.jupiter.api.Assumptions.assumeFalse(log.isTraceEnabled(),
                "trace is on here, so this cannot observe a suppressed statement");
        int before = calls.get();
        log.trace("never printed: {}", deferred);
        assertThat(calls)
                .as("the escaping was paid for a line the level had already discarded")
                .hasValue(before);
    }

    @Test
    @DisplayName("a batch limit nobody would pass is clamped rather than believed")
    void clampsANonsenseLimit() {
        // shown = min(limit, size) went negative, the loop ran zero times, and the tail
        // counted size - shown: a list of two rendered "[, and 3 more]".
        assertThat(Quoted.each(List.of("a", "b"), 0)).isEqualTo("[, and 2 more]");
        assertThat(Quoted.each(List.of("a", "b"), -1)).isEqualTo("[, and 2 more]");
    }

    @Test
    @DisplayName("the rule has one spelling, so a key refusal and a log agree")
    void isTheRuleMemoryKeysUses() {
        // MemoryKeys refuses a key that could rewrite a listing and quotes it back to the
        // model; this quotes the same shapes for an operator. Two spellings of one rule can
        // drift, and the drift would be invisible — which is how the log site came to have
        // no rule at all while the model-facing one had a documented one.
        for (int cp : new int[] {'\n', '\r', 0x2028, 0x2029, 0x001B, 0x202E, 0x200D, 0xFEFF,
                0xD800, 0x0000}) {
            assertThat(Quoted.restructures(cp))
                    .as("U+%04X was not treated as able to restructure a line", cp)
                    .isTrue();
        }
        // Asserted against MemoryKeys' own behaviour rather than against a list copied from
        // it. The first version of this test was named for a rule having one spelling and
        // checked a hardcoded array, so the drift it was named after would have gone
        // unnoticed — and the rule did in fact still have two spellings.
        for (int cp : new int[] {'\n', 0x2028, 0x001B, 0x202E, 0xFEFF, 0xD800}) {
            String key = "a" + new String(Character.toChars(cp)) + ".md";
            assertThatThrownBy(() -> dev.agentkit.core.memory.MemoryKeys.normalize(key))
                    .as("MemoryKeys accepted U+%04X, which Quoted calls restructuring", cp)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int cp : new int[] {'a', 0x00E9, 0x4E2D, 0x1F600}) {
            String key = "a" + new String(Character.toChars(cp)) + ".md";
            assertThat(dev.agentkit.core.memory.MemoryKeys.isNormalized(key))
                    .as("MemoryKeys refused U+%04X, which Quoted calls harmless", cp)
                    .isTrue();
        }
        for (int cp : new int[] {'a', '/', '.', 0x00E9, 0x4E2D, 0x1F600, ' ', '%'}) {
            assertThat(Quoted.restructures(cp))
                    .as("U+%04X was treated as dangerous", cp)
                    .isFalse();
        }
    }
}
