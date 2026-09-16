package dev.agentkit.core.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Durable, cross-session memory: a keyed store of text documents the agent
 * reads and writes to carry knowledge from one run to the next.
 *
 * <p>Paths are forward-slash-separated relative keys (e.g. {@code "facts/user.md"}).
 * Implementations must confine keys to their root — rejecting traversal, and any path
 * that leaves through a symbolic link — see {@link FileMemoryStore}.
 *
 * <p><strong>That confines keys, not the directory.</strong> It is a guarantee against the
 * model naming a key that reaches out, which is the threat this store is for. It is not a
 * guarantee against anyone who can create names under the root: hard links, and a link
 * swapped into a directory component between the check and the open, both reach past it.
 * {@link FileMemoryStore}'s class javadoc sets out what is and is not covered. If the root
 * is somewhere a hostile local process can write, the boundary has to be the filesystem's.
 *
 * <p><strong>One key contract, and every implementation owes it.</strong> Call
 * {@link MemoryKeys#normalize} rather than reimplementing it: whitespace is stripped from
 * each segment, {@code ./} and trailing slashes removed, {@code //} collapsed,
 * {@code a/../b} folded to {@code b}. So {@code "  a.md  "} and {@code "a.md"} are one
 * key, and a store treating them as two would disagree with the next store the same code
 * runs against — which testing tends not to catch, since tests run in memory and
 * deployments run on disk. Blank keys and segments, absolute paths, the root itself,
 * traversal above it, and keys carrying characters that could restructure a listing are
 * rejected with {@link IllegalArgumentException}.
 *
 * <p>The consequence worth relying on: <em>every key {@link #list} reports is well-formed,
 * so {@link #read} resolves it rather than rejecting it.</em> Not a liveness guarantee —
 * a durable root is shared across runs, so a document may be gone by the time the read
 * lands — but a caller looping list-then-read never trips over a name it cannot use.
 *
 * <p><strong>Absence is an answer, not a failure.</strong> This is the decision #83 asked
 * for. An operation is total exactly when <em>nothing is there</em> already satisfies what
 * it was for: {@link #delete} wants no memory at the key, so a key naming nothing has
 * already succeeded; {@link #read}, {@link #exists} and {@link #list} are asking rather
 * than changing. {@link #write} and {@link #append} are not satisfied by absence, so they
 * must be able to refuse.
 *
 * <p>So for a well-formed key naming nothing this store can reach — one that resolves
 * through a symbolic link out of a durable root, a name the store did not write — the four
 * asking operations answer empty, {@code false}, {@code false} and a list, where the two
 * writing ones throw. That is the answer a map gives for the same key, which is the whole
 * point, and it means a caller looping {@code list} then {@code read} is not asked to catch
 * anything for a name it was just handed.
 *
 * <p><strong>A symbolic link is not a memory</strong> (#99). A durable store answers for
 * the documents it wrote, and it never writes a link, so a key that reaches one names
 * nothing the store holds — {@code read} is empty, {@code exists} and {@code delete} are
 * {@code false}, {@code list} does not report it, and {@code write} and {@code append}
 * refuse, since absence never satisfies those two. The link and whatever it points at are
 * left exactly where they are: refusing to treat something as a memory is not the same as
 * deciding it should not exist.
 *
 * <p>Said of the <em>whole</em> key, not of its last name. A key is reached through a link
 * if any component of it is one, so {@code aliasDir/note.md} is as absent as
 * {@code alias.md} when {@code aliasDir} is a link. Anything narrower leaves the defect
 * intact in the shape that costs most: a directory link makes {@code read} answer and
 * {@code list} stay silent, and {@code delete} then unlinks the document a different key
 * names.
 *
 * <p>This settles a disagreement rather than adding a rule. {@code list} had always skipped
 * links — it walks without following them, so neither a linked name nor anything under a
 * linked directory is a regular file to it — while the other three followed one and found a
 * document. A memory reachable by key and invisible to every listing is one an agent will
 * never look at again. Following links consistently instead was the other way to agree, and
 * it costs more than it looks: {@code list} would have to follow them too, which turns a
 * cheap walk into a containment re-check for every entry it reports, and two keys would
 * name one document, so an agent compacting its memory would see a fact twice and could
 * read it as corroborated. Not following is also what the rest of the store already did.
 * (The loop a self-referencing link makes is <em>not</em> among the costs, though an
 * earlier draft of this paragraph said it was: a following walk reports one as a
 * {@code FileSystemLoopException} to the handler {@code list} already has.)
 *
 * <p><strong>What no contract can promise away is the medium.</strong> Every operation here
 * may throw {@link java.io.UncheckedIOException} — an unreadable file, a directory the
 * process may not open, a disk that has gone away. A document {@code list} reported may
 * still fail to be read for a reason that has nothing to do with the key. The promise above
 * is about <em>reachability</em>, which is the store's business, not about I/O, which is
 * not.
 *
 * <p>{@link #write} and {@link #append} change the world, so they must be able to report
 * that they did not. Seven reasons, and every implementation owes the first two
 * identically:
 *
 * <ul>
 *   <li><strong>The key collides with the hierarchy.</strong> Keys form a tree — that is
 *       what {@code list("notes/")} means — so a key is a document or a folder and never
 *       both. Writing {@code "a/b"} when {@code "a"} holds a document, or {@code "a"} when
 *       anything lives under it, is {@link IllegalArgumentException} with a reason the
 *       caller can act on by choosing another key.</li>
 *   <li><strong>The value is not text UTF-8 can carry.</strong> Half a surrogate pair is
 *       not a character, and a store that wrote one down would either substitute it —
 *       {@code String.getBytes(UTF_8)} yields {@code 0x3F}, a literal {@code '?'}, silently
 *       — or fail. So it is refused, in the reason channel, naming the code point: the
 *       model chose the string and can choose another, which is the whole test for which
 *       channel a refusal belongs in. The one rule that binds a value as well as a key, and
 *       the second every implementation owes, since a {@code TreeMap} will hold the string
 *       quite happily and {@code InMemoryMemoryStore} did until #147.
 *
 *       <p>It was in the <em>medium</em> channel until #245, in both stores, which is where
 *       the paragraph below used to leave it as a named residual. That is the defect the
 *       SPI section names in the abstract — "a refusal the caller could fix, reported in
 *       the second channel, is a dead end" — and one of the two stores announcing that its
 *       medium had refused has no medium at all.</li>
 *   <li><strong>The key leaves the store.</strong> A durable root resolves keys against a
 *       directory, and one that lands outside it — through a planted symbolic link —
 *       refuses rather than writing there. A store with no filesystem under it has nothing
 *       to leave, so this is the first of the four reasons not every implementation
 *       can raise.</li>
 *   <li><strong>The key is reached through a symbolic link.</strong> Including one that
 *       stays inside the store, and including one in a directory component rather than the
 *       last name. Absence is the answer the four asking operations give for such a key,
 *       and absence never satisfies a write, so these two refuse instead of following the
 *       link to a document some other key names. The second reason a store with no
 *       filesystem under it cannot raise.</li>
 *   <li><strong>Something that is not a document stands at the key.</strong> Distinct from
 *       the collision above, and it took #115 to notice: a folder holding only symbolic
 *       links — or a FIFO, a socket, a device node — holds no <em>key</em>, so the
 *       hierarchy is not in collision, and the folder still cannot be got out of the way.
 *       {@link IllegalArgumentException}, since another key works. The third reason a store
 *       with no filesystem under it cannot raise.
 *
 *       <p>Or one of those standing at the key <em>itself</em> rather than inside a folder
 *       (#145), which this paragraph did not cover and which failed worse: a FIFO was
 *       reported free and handed to the open, where it blocked forever with no error and
 *       nothing to time out, and a device node accepted the write and stored nothing. Both
 *       are refused now.
 *
 *       <p>And at an <em>ancestor</em> component, which is #188 and which this paragraph
 *       used to say was excluded: a FIFO at {@code a} refuses {@code a/b.md} by naming
 *       {@code a} as the thing standing in that path that is not a folder. It used to be
 *       the medium instead — the {@code ENOTDIR} the open returned was one
 *       {@link java.io.IOException} among others and got folded into "Memory operation
 *       failed.", so the model was told nothing it could act on for {@code a/b.md} while
 *       being told exactly what to do about {@code a}. Reaching the key one component at a
 *       time is what makes the two separable: the walk knows which component it stopped
 *       at.
 *
 *       <p>An ancestor that merely cannot be <em>searched</em> is still the medium, and
 *       deliberately so rather than for want of the information. What every refusal in this
 *       list buys the model is "choose another key", and under a directory it may not search
 *       every key fails alike — so a refusal there would be advice that does not work, which
 *       is worse for the caller than an honest medium error.
 *
 *       <p>The justification the paragraph above gives for a folder is that it "cannot be
 *       got out of the way", and that is <strong>not</strong> why a node at the key is
 *       refused: {@code unlink} would remove a FIFO perfectly well. It is refused because a
 *       store that did not create it should not destroy it, and because the alternative —
 *       replacing it — is what #186 owns, together with the race that makes replacing
 *       attractive.</li>
 *   <li><strong>Whether the key is free could not be decided within a bound.</strong>
 *       Deciding it means walking whatever stands under the key, and the model chooses that
 *       tree: {@code write("a/dN/x.md")} then {@code delete("a/dN/x.md")} leaves the folder
 *       behind, so N pairs of ordinary tool calls leave N folders that {@code list("")}
 *       reports as no keys at all and that every later write to {@code a} pays for — 100,000
 *       of them measured at 7 seconds a write (#146). The walk now stops at a bound on
 *       entries and on folder depth and refuses with a reason, rather than reporting a
 *       subtree it did not finish looking at as holding no document.
 *
 *       <p>It never turns a write that would have succeeded into one that refuses. Stated
 *       that way rather than as "a document stops the walk", which is the shorter claim and
 *       is not quite true: the walk counts an entry before it looks at it, so a document
 *       lying past the entry budget is not reached, and directory order is not something a
 *       caller chooses or a test can pin. What holds regardless is that a write succeeds
 *       only where the key is free or holds nothing but empty folders — and a subtree big
 *       enough to reach the bound is neither of those, it is the tree #146 exists to refuse.
 *       A document the walk does reach settles the collision first and refuses under the
 *       first reason in this list instead. The refusal says "none of the ones this store
 *       looked at is a document" for exactly this reason, and means it. The fourth reason a
 *       store with no filesystem under it cannot raise — a map decides freedom by lookup and
 *       has no tree to walk.</li>
 *   <li><strong>The medium refused.</strong> A disk that is full or read-only is an
 *       {@code UncheckedIOException}, and no contract can promise it away.
 *
 *       <p><strong>One shape of it is worth naming here, because it is a property of the
 *       medium rather than of its state</strong> (#273). A store whose keys live in a
 *       namespace it does not own can only replace a document by asking that namespace to
 *       put a new one at a name already holding one, in a single step; the SPI section
 *       below states that as the one requirement such a store has of its medium, and says
 *       why it cannot be assembled out of two steps instead. Where the medium will not, a
 *       key can take a document <em>once</em>: whichever write puts one there succeeds,
 *       and every write that would replace it arrives here — so a store that looks
 *       entirely well for the length of one session refuses every revision after it.
 *       Measured: {@link FileMemoryStore} on a Jimfs 1.3.0 unix root passes every clause
 *       of {@code MediumBackedMemoryStoreContract} except the two that put a document at
 *       a key already holding one — 24 of 26 as they were counted.
 *
 *       <p>It is the medium channel and belongs in it — nothing the caller passed was
 *       wrong, and no other key does better, so there is no advice to give. That is
 *       precisely why it is written down: a refusal the caller cannot act on is one the
 *       person choosing the medium has to act on instead, and this list is where they
 *       look.</li>
 * </ul>
 *
 * <p>An {@code IllegalArgumentException} rather than a returned result, because the model
 * is the caller that matters and {@code MemoryTools} hands its message through while
 * reducing everything else to "Memory operation failed." The exception is the reason
 * channel. A result type would have to carry the same message, and — since the medium can
 * fail regardless — would leave two failure channels where there is now one.
 *
 * <p><strong>Which of these a refusal is, as something a program can compare</strong>
 * (#257). {@link MemoryRefusal} enumerates them — the six above that are not the medium,
 * and the one that comes before all of them, an argument that was not a key at all.
 * The message is the payload for the model and prose for everybody else, so nothing
 * could check that two stores refusing the same call refused it for the same reason —
 * {@code MemoryStoreDifferentialTest} classified a call by channel, and read "both refused
 * with an argument error" as agreement even where one store was blaming the key and the
 * other the value. Every refusal in this package now carries a {@link MemoryRefusal}, read
 * back with {@link MemoryRefusal#behind}, and the fuzzer compares it alongside the channel.
 *
 * <p>It is not a new obligation on an implementation outside this repository.
 * {@link MemoryRefusal#behind} answers empty for a plain {@code IllegalArgumentException},
 * which is what a conforming store has always raised, and a differential oracle then falls
 * back to comparing channels exactly as before. Carrying one is how a store buys the
 * stronger check, not how it stays conforming — see {@link MemoryRefusal} for why this was
 * preferred to asserting the message, or the order the arguments are validated in.
 *
 * <p>What is <em>not</em> a reason any more is length. A key too long to be written down is
 * refused by {@link MemoryKeys} on every operation of both stores, rather than discovered by
 * one of them at open time — see there for the limits and why they are measured in encoded
 * bytes.
 *
 * <p>The direction was chosen against the alternative of making the map fail wherever the
 * filesystem does. That would mean reimplementing enough of a filesystem in a {@code HashMap}
 * to be approximately right, and an approximate emulation is worse than either honest
 * extreme: the store under test would fail in ways production does not, which is the same
 * defect as the one being fixed, pointing the other way.
 *
 * <p><strong>Upgrading an existing root.</strong> Before this contract was enforced on
 * both sides, {@link FileMemoryStore} passed keys through unnormalised, so a root may hold
 * files whose names are not keys — {@code "  a.md  "} with the spaces really in the name.
 * Those are no longer reachable under any key and are left in place rather than renamed;
 * {@code list} skips them and logs what it skipped, so they can be found and moved. The
 * documents are on disk, not lost.
 *
 * <p>Naming files after the <em>encoded</em> key adds two more such populations, for the
 * same reason and with the same handling. {@link FileMemoryStore} now writes each key
 * percent-encoded — every code point outside printable ASCII as its UTF-8 bytes in
 * {@code %XX}, and {@code %} itself escaped, so {@code facts/café.md} is the file
 * {@code facts/caf%C3%A9.md} and {@code facts/user.md} is unchanged. A root written by an
 * earlier version therefore holds two kinds of orphan: names with a literal
 * {@code %} in them, which were ordinary keys on every host, and raw non-ASCII names, which
 * could only ever be written where the locale carried them. Both are skipped and logged
 * rather than renamed. Renaming them back would need this interface to decide what the
 * bytes in a directory entry meant, which is the question the encoding exists to stop
 * asking.
 *
 * <p>One key that was accepted yesterday is now refused by <em>both</em> stores: a key
 * carrying an unpaired surrogate. UTF-8 cannot encode one, so any store that writes it
 * down substitutes a replacement and two keys become one document — a store that loses a
 * memory rather than refusing one.
 *
 * <p>And an operator who aliased documents with symbolic links inside the root will find
 * those keys answer empty, {@code false} and {@code false}, and that writing to one is
 * refused (#99) — whether the link is the key's last name or a directory two levels above
 * it. The links themselves are untouched; what changed is that the store no longer claims
 * what they point at. {@code list} names every link it finds in a warning, so the aliases
 * that went inert can be found rather than discovered by their absence. The remedy is to
 * remove the link, which is the only thing that frees the key — no store operation will,
 * since {@code delete} answers {@code false} for it and {@code write} refuses it. Copying
 * the document under a second key is what replaces the alias once the link is gone.
 * Pointing the <em>root</em> itself at another location is supported and always has been,
 * but that moves the whole store rather than aliasing one document within it.
 *
 * <h2>What an implementation outside this repository owes (#153)</h2>
 *
 * <p>This is an SPI, so a store somebody else writes is a runner of every rule above that
 * no audit here can enumerate — the defect this repository keeps finding (#113, #131,
 * #163, #179), in the one shape where {@code grep} cannot find the missing runner. Until
 * this section existed, an implementor learned the rules by reading {@link FileMemoryStore}:
 * a sibling rather than a contract, and one whose javadoc is mostly about inodes,
 * {@code rename(2)} and symbolic links, none of which a store with no filesystem under it
 * can honour or violate.
 *
 * <p><strong>The line, and the question that draws it: could a {@code TreeMap} break
 * this?</strong> If it could, it is the contract and every implementation owes it. If
 * breaking it needs a medium — a link, an inode, a FIFO, a mode bit — it is that medium's
 * business, and the contract states only the <em>consequence</em> a caller can rely on
 * whatever the medium is. Getting the line wrong fails both ways: too strict ("reject
 * symbolic links") and no map, database or object-store implementation can conform, so
 * nobody runs the suite; too loose ("keys are strings, do your best") and the contract
 * would not have caught #83, #96 or #97, each of which was one store accepting what the
 * other refused.
 *
 * <p>So, owed by everyone:
 *
 * <ul>
 *   <li><strong>What a key is</strong> — {@link MemoryKeys#normalize}, including the length
 *       budget. It is counted in encoded bytes because that is what a filesystem charges,
 *       and it binds a map too, because a store that took a key its sibling refuses is the
 *       divergence whichever of them has the disk.</li>
 *   <li><strong>That a key is a document or a folder and never both</strong> —
 *       {@code MemoryNamespace}, which a filesystem enforces for free and a map does
 *       not.</li>
 *   <li><strong>That absence is an answer</strong> for {@link #read}, {@link #exists},
 *       {@link #delete} and {@link #list}, and is not one for {@link #write} and
 *       {@link #append}.</li>
 *   <li><strong>That every key {@link #list} reports is one {@link #read} resolves</strong>
 *       rather than rejects.</li>
 *   <li><strong>That a value comes back exactly as it went in</strong> — see below.</li>
 *   <li><strong>Two failure channels and no third.</strong> {@link IllegalArgumentException}
 *       carries a reason the caller can act on by choosing differently, and is the only
 *       thing {@code MemoryTools} shows the model; everything else is
 *       {@link java.io.UncheckedIOException} and reaches the model as "Memory operation
 *       failed." A refusal the caller could fix, reported in the second channel, is a dead
 *       end — which is a defect even when the refusal is right.</li>
 * </ul>
 *
 * <p>And owed only where there is a medium other processes can write to — stated as a
 * consequence rather than as a mechanism, so it means something for a bucket or a table as
 * well as for a directory: <strong>a document at a key-shaped name is a memory whoever put
 * it there</strong> (a restored backup, a shared volume — otherwise "durable" would mean
 * "written by this process"), and <strong>anything else at a key names nothing</strong>:
 * the four asking operations answer absence, the two writing ones refuse with
 * {@link IllegalArgumentException}, and neither destroys it, because a store that did not
 * create a thing has no business removing it. Everything {@link FileMemoryStore} says about
 * links, inodes, {@code openat} and {@code rename(2)} is how one implementation keeps that
 * pair of promises on one medium; none of it is the contract.
 *
 * <p>That pairing is also what makes the two stores comparable at all. Absence is exactly
 * what a map answers for a key it does not hold, so a planted entry leaves the four asking
 * operations in agreement with nothing exempted, and only the two writing ones diverge —
 * predictably. {@code MemoryStoreDifferentialTest} is built on that (#147).
 *
 * <h3>What such a store needs of its medium (#273)</h3>
 *
 * <p>Everything above is owed by the store to its caller. This is owed the other way, and
 * it is the one requirement that does not follow from "a namespace holding documents under
 * names": <strong>the medium must be able to put a document at a key that already holds
 * one in a single step</strong>. Stated as a consequence and not as a mechanism, like the
 * pair above it, because every shared namespace spells it differently — {@code rename(2)} for a directory, an overwriting {@code PUT} for a
 * bucket, {@code UPDATE} for a row. A medium that offers none of them cannot back a
 * conforming store, and this is the sentence an implementor should read before choosing
 * one.
 *
 * <p><strong>The two-step substitute is not equivalent, which is what makes this a
 * requirement rather than an implementation's preference.</strong> Remove what is at the
 * key, then put the new document there: it satisfies every clause in this contract as
 * written, since all of them are single-threaded. What it adds is an interval in which the
 * key holds nothing — so {@link #read} answers empty and {@link #list} stays silent for a
 * memory that was never deleted, and a store whose {@link #append} is a read-modify-write
 * can read that emptiness and write back only what was being appended, losing the
 * document without any other process being involved. "Replacing" would have become
 * "deleting, then creating", which is a different thing to everyone watching.
 *
 * <p>Two things about that argument, both of which cut against its stronger forms. It is
 * <em>not</em> the check-to-open window #145 closed nor the path-re-walk window #168
 * closed — neither step of a two-step replace opens the key, and both can be made relative
 * to a held descriptor, so those stay shut. And no clause here checks it: as the section
 * below says, what a medium does under a race is not reachable from a single-threaded
 * conformance suite, and this is prose for the same reason the logging rule is. It is
 * stated rather than checked because an implementor who has to discover it as a race will
 * discover it in production.
 *
 * <p>{@link FileMemoryStore} is where the measurement lives, and it is worth one sentence
 * here because it is the shape a reader will meet first: the branch that reaches keys
 * through an open directory finishes with
 * {@link java.nio.file.SecureDirectoryStream#move}, whose specification makes replacing
 * the target optional and which carries no options argument at all — so on that branch
 * the store cannot even ask, and the medium decides. Whether the contract suite should
 * exempt a medium that says no is answered in {@code MediumBackedMemoryStoreContract}: it
 * should not, and why.
 *
 * <h2>A key is a name; a value is a body</h2>
 *
 * <p>The two arguments of {@link #write} are treated oppositely and nothing said so, which
 * is #153's second implicit obligation — {@code Quoted}'s distinction, which this interface
 * never stated.
 *
 * <p>A <strong>key</strong> is a name. It is normalised, its segments are stripped, and it
 * is refused outright if it carries anything that could restructure a listing, because
 * {@code MemoryTools} renders {@link #list} as one key per line into a tool result the
 * model reads back — so a newline in a key forges entries. Where a key appears in a
 * message or a log it goes through {@code Quoted} first: keys are third-party text, the
 * model chose them, and a key carrying a line terminator has already written entries of its
 * own into an operator's log once (#98). A store that echoed a rejected key raw would do on
 * the failure path exactly what the rejection is for.
 *
 * <p>A <strong>value</strong> is a body. It is not stripped, not normalised, not escaped,
 * and not truncated; it comes back byte-for-byte, leading spaces, newlines, {@code %}
 * signs, fence-shaped text and all. <strong>Fencing a value is the caller's job, not the
 * store's</strong> — the store cannot know what the eventual renderer needs, and a helpful
 * strip would corrupt every value that legitimately contains what it stripped. What a store
 * must not do is log a value: it is the largest and least trustworthy thing it holds.
 *
 * <p>One rule does bind both, and it is the same rule for the same reason. A value UTF-8
 * cannot encode — half a surrogate pair — is <strong>refused</strong> rather than
 * substituted or accepted. Substituting is what {@code String.getBytes(UTF_8)} does, and it
 * loses the value while reporting success; accepting is what {@code InMemoryMemoryStore}
 * did until the fuzzer was given a content vocabulary (#147), and it made the store under
 * test hold what the store in production refuses.
 *
 * <p>Both refuse with {@link IllegalArgumentException} since #245, naming the offending
 * code point escaped. This paragraph used to end "today both refuse, in the medium channel;
 * that channel is wrong for a string the caller chose and could change, and moving both to
 * {@link IllegalArgumentException} is a named residual rather than a decision this
 * paragraph is dodging." It was the right residual and it is closed; the correction is left
 * visible rather than swapped in silently, because the argument for which channel a refusal
 * belongs in is the useful half of it and reads the same either way round.
 *
 * <h2>What checks any of this</h2>
 *
 * <p>Nothing, if it stays prose — which is what #128 calls a type that says a thing without
 * having made it true, and a paragraph is not better placed than a type. So the clauses
 * above are also an abstract test: {@code MemoryStoreContract} in this module's test-jar,
 * with {@code MediumBackedMemoryStoreContract} adding the planted-entry half. Extend one of
 * them, return your store, and you find out. Both stores here run it, and it is where the
 * shared clauses live rather than being restated per implementation.
 *
 * <p><strong>Two of the clauses above it cannot check, and saying which is the point.</strong>
 * What a store <em>logs</em> is not observable through this interface, so "keys through
 * {@code Quoted}, values never" stays prose — the nearest thing that is checked is that a
 * refusal <em>message</em> escapes the key it names, which is the same rule on the one
 * channel a test can see. And what a medium does under a <em>race</em> is not reachable from
 * a single-threaded conformance suite at all; {@code MemoryRaceTest} is where that is
 * measured, for one implementation, and no portable clause could stand in for it. A suite
 * that pretended to check either would be worse than one that says it does not.
 *
 * <p><strong>Never store secrets here.</strong> Memory persists and is replayed
 * into future contexts; a credential written once is exposed to every later run.
 */
public interface MemoryStore {

    /** A non-persistent, in-process store. */
    static MemoryStore inMemory() {
        return new InMemoryMemoryStore();
    }

    /**
     * A durable store backed by the directory {@code root}.
     *
     * <p>{@code containment} has no default here for the same reason it has none on
     * {@link FileMemoryStore}: the containment a file-backed store documents is unavailable on
     * every platform whose JDK returns no {@code SecureDirectoryStream}, which is every
     * platform except Linux. See {@link Containment}.
     */
    static MemoryStore file(Path root, Containment containment) {
        return new FileMemoryStore(root, containment);
    }

    /** Reads the content at {@code path}, or empty if it does not exist. */
    Optional<String> read(String path);

    /** Writes (creating or replacing) {@code content} at {@code path}. */
    void write(String path, String content);

    /**
     * Appends {@code content} to {@code path}, creating it if absent.
     *
     * <p><strong>Read-modify-write, and two things follow from that</strong> (#189). A
     * durable store cannot append by opening the key — a FIFO arriving between the check
     * and the open blocks the thread forever, with no error, no result and nothing to time
     * out, which {@link FileMemoryStore} measured at roughly one append in five. Closing
     * that means reading the document, concatenating, and replacing the whole thing. So:
     *
     * <ul>
     *   <li><strong>An append costs the size of the document, not the size of what is being
     *       appended.</strong> Repeatedly appending to one key is quadratic in total. The
     *       figures are in {@code FileMemoryStore.appendWithoutFollowing}; for the sizes an
     *       agent's memory actually reaches they are small, and the caller in this
     *       repository already read the whole document before every append anyway.</li>
     *   <li><strong>Two appends racing on one key no longer both land</strong>, and the
     *       figure is not small. Eight threads appending 200 lines each to one key: all
     *       1,600 landed before, because the kernel makes {@code O_APPEND} atomic; 367, 401
     *       and 578 land now, so 64–77% are silently gone. Nothing ever promised otherwise
     *       — {@link FileMemoryStore} says it is not thread-safe for concurrent writers to
     *       the same path and that its checks are not locks, and so does {@code LessonBook}
     *       — but it did happen to work, and a behaviour that changes is worth stating
     *       whether or not it was a promise. One writer per key, or a lock above this
     *       interface.
     *
     *       <p>Weighed, not waved through: what it buys is that a FIFO under the key no
     *       longer blocks the appending thread forever, which measured at 173 in 900 and is
     *       unobservable — an agent that simply stops, with nothing in the transcript. A
     *       line lost under a usage this store already disavows is the cheaper of the
     *       two.</li>
     * </ul>
     *
     * <p>Both of these bring the two stores <em>together</em> rather than apart, which is
     * worth saying because divergence between them is what {@code MemoryStoreDifferentialTest}
     * exists to catch. {@link #inMemory} appends with {@code TreeMap.merge} and
     * {@code String::concat}: already a whole-document copy, and already a plain map with no
     * safety for concurrent writers whatsoever.
     */
    void append(String path, String content);

    /**
     * Deletes the document at {@code path}; returns whether anything was removed.
     *
     * <p>{@code false} for a key that names nothing, and for one that names something which
     * is not a document — a folder, or a link that no longer resolves to a file. Answering
     * {@code true} there would report a memory forgotten that was never held, which a model
     * acts on.
     */
    boolean delete(String path);

    /** Whether {@code path} exists. */
    boolean exists(String path);

    /**
     * Lists existing paths that start with {@code prefix} (empty prefix lists
     * all), sorted. Matching is a <em>lexical</em> string prefix, not a
     * path-segment prefix — {@code "notes"} matches both {@code "notes/a"} and
     * {@code "notesheet"}; pass {@code "notes/"} to scope to a directory.
     */
    List<String> list(String prefix);
}
