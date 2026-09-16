package dev.agentkit.chat;

import dev.agentkit.core.util.Quoted;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who is watching a conversation, and what they have missed.
 *
 * <h2>Publishing must not be able to slow a run down</h2>
 *
 * <p>This is the constraint the whole design is built around. The thread calling
 * {@link #publish} is the thread running the agent — an observer callback fires on it — and a
 * subscriber is, in the end, a socket to a browser on a train. If publishing could block on
 * that socket, a laptop closing its lid would stall the run behind it.
 *
 * <p>So every subscriber has a bounded queue and {@link #publish} only ever offers to it. A
 * subscriber that cannot keep up is <strong>dropped</strong>, not waited for: its queue is
 * abandoned and {@link Subscriber#dropped()} becomes true, which is what the client sees
 * instead of a silently truncated stream. It reconnects from its last sequence and the replay
 * buffer gives it everything it missed — which is why dropping is an inconvenience rather
 * than a loss.
 *
 * <p>There is no dispatcher thread. The subscriber's own consumer — the thread writing the
 * server-sent-event stream — drains the queue, so the only thread that ever blocks on a slow
 * client is the one already dedicated to it.
 *
 * <h2>Reconnecting is a resume</h2>
 *
 * <p>Each conversation keeps the last {@link #REPLAY} events. A client subscribing from
 * sequence 41 has those events already in its queue before {@link #subscribe} returns, so
 * there is no window between "what I replayed" and "what I now receive" for an event to fall
 * through. That window is exactly what the polling consoles had, and it is why they re-read
 * the whole transcript on every tick.
 */
public final class ChatEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ChatEvents.class);

    /**
     * How many events per conversation are kept for a client that reconnects.
     *
     * <p>Sized for the gap a reconnect actually covers — a page reload, a laptop waking, a
     * proxy dropping an idle stream — rather than for the whole history, which is what the
     * transcript is for. A client that has fallen further behind than this is told so and
     * re-reads the conversation, which is correct: at that distance the transcript is cheaper
     * than the events that produced it.
     */
    public static final int REPLAY = 512;

    /**
     * How many events a subscriber may be behind before it is dropped.
     *
     * <p>Smaller than {@link #REPLAY} on purpose: being dropped costs a reconnect, and a
     * reconnect is only cheap while the replay buffer still holds what was missed. A queue
     * deeper than the buffer would let a client fall far enough behind that dropping it loses
     * events, which is the one outcome this design is meant to avoid.
     */
    public static final int QUEUE = 256;

    private final Clock clock;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, Deque<ChatEvent>> replay = new ConcurrentHashMap<>();
    private final Map<String, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

    /**
     * One lock per conversation, guarding the sequence, the buffer and the subscriber list
     * together.
     *
     * <p>Together is the point, and the first version did not have it. {@code subscribe}
     * registered the subscriber and <em>then</em> loaded the replay, so an event published in
     * between reached the queue ahead of events older than itself — a client that reconnected
     * mid-turn could be handed the answer before the tool call that produced it, which is
     * precisely the ordering this whole class exists to guarantee.
     *
     * <p>Cheap to hold: publishing under it does an append, a trim and a non-blocking offer
     * per subscriber, and never touches a socket. Per conversation rather than global so one
     * busy thread does not serialise every other conversation in the process.
     */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private Object lockFor(String conversationId) {
        return locks.computeIfAbsent(conversationId, key -> new Object());
    }

    public ChatEvents() {
        this(Clock.systemUTC());
    }

    public ChatEvents(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * One client watching one conversation.
     *
     * <p>Closed by the consumer when its socket goes, or abandoned by {@link #publish} when it
     * stops keeping up. Either way it stops receiving; {@link #dropped()} is how the consumer
     * tells the two apart, because they mean different things to the client — one is "you
     * closed this" and the other is "you missed some, reconnect from your last sequence".
     */
    public final class Subscriber implements AutoCloseable {

        private final String conversationId;
        private final ArrayBlockingQueue<ChatEvent> queue = new ArrayBlockingQueue<>(QUEUE);
        private volatile boolean dropped;
        private volatile boolean closed;
        private volatile long lastSequence;

        private Subscriber(String conversationId, long from) {
            this.conversationId = conversationId;
            this.lastSequence = from;
        }

        /**
         * The next event, or null if none arrived within {@code timeout}.
         *
         * <p>A timeout is not an error: an idle conversation produces nothing, and the caller
         * uses the gap to write a keep-alive so a proxy does not close the stream.
         */
        public ChatEvent poll(Duration timeout) throws InterruptedException {
            ChatEvent event = queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (event != null) {
                lastSequence = event.sequence();
            }
            return event;
        }

        /** Whatever is waiting, without blocking. */
        public List<ChatEvent> drain() {
            List<ChatEvent> taken = new ArrayList<>();
            queue.drainTo(taken);
            if (!taken.isEmpty()) {
                lastSequence = taken.get(taken.size() - 1).sequence();
            }
            return taken;
        }

        /** The sequence this subscriber has seen up to, for reconnecting after a drop. */
        public long lastSequence() {
            return lastSequence;
        }

        /** Whether this subscriber fell behind and was abandoned rather than closed. */
        public boolean dropped() {
            return dropped;
        }

        /** Whether this subscriber is still receiving — false once closed or forgotten. */
        public boolean active() {
            return !closed && !dropped;
        }

        @Override
        public void close() {
            closed = true;
            // Through the map's own value rather than getOrDefault(..., List.of()): the
            // default is immutable, so removing from it throws — which is what closing a
            // subscriber after its conversation had been forgotten used to do.
            List<Subscriber> watching = subscribers.get(conversationId);
            if (watching != null) {
                watching.remove(this);
            }
        }
    }

    /**
     * Watches a conversation from just after {@code fromSequence}.
     *
     * <p>Everything still in the replay buffer after that sequence is in the subscriber's
     * queue before this returns, so there is no gap between the replay and the live stream.
     * Pass 0 to start from as far back as the buffer goes.
     */
    public Subscriber subscribe(String conversationId, long fromSequence) {
        Objects.requireNonNull(conversationId, "conversationId");
        Subscriber subscriber = new Subscriber(conversationId, fromSequence);
        // Under the lock, which is the whole of the fix: a concurrent publish is either
        // wholly before this — and therefore already in the replay buffer — or wholly after
        // it, and lands in the queue behind everything replayed. The order of the two
        // statements below is NOT what makes that true, and an earlier comment here claimed
        // it was; swapping them changes nothing, which is how the claim was found to be
        // wrong. Without the lock, neither order is safe.
        synchronized (lockFor(conversationId)) {
            for (ChatEvent missed : since(conversationId, fromSequence)) {
                offer(subscriber, missed);
            }
            subscribers.computeIfAbsent(conversationId, key -> new CopyOnWriteArrayList<>())
                    .add(subscriber);
        }
        return subscriber;
    }

    /** Everything still buffered for a conversation after {@code sequence}. */
    public List<ChatEvent> since(String conversationId, long sequence) {
        synchronized (lockFor(conversationId)) {
            Deque<ChatEvent> buffered = replay.get(conversationId);
            return buffered == null ? List.of()
                    : buffered.stream().filter(event -> event.sequence() > sequence).toList();
        }
    }

    /**
     * Records an event and hands it to everyone watching.
     *
     * <p>Never blocks and never throws. An event stream that could fail a run would make
     * watching a run change what the run does, which is the one thing an observer must not
     * do — and the framework already absorbs a throwing observer for exactly this reason.
     * Publishing being total means this layer does not have to rely on that.
     *
     * @return the event as published, with its sequence
     */
    public ChatEvent publish(String conversationId, String turnId, ChatEvent.Type type,
            String runId, String runName, Map<String, Object> data) {
        synchronized (lockFor(conversationId)) {
            // The sequence is assigned in here too, so sequence order, buffer order and queue
            // order are the same order by construction rather than by argument.
            long sequence = sequences.computeIfAbsent(conversationId, key -> new AtomicLong())
                    .incrementAndGet();
            ChatEvent event = new ChatEvent(sequence, conversationId, turnId, type, runId,
                    runName, data, clock.instant());
            Deque<ChatEvent> buffered = replay.computeIfAbsent(conversationId,
                    key -> new ArrayDeque<>());
            buffered.addLast(event);
            while (buffered.size() > REPLAY) {
                buffered.removeFirst();
            }
            for (Subscriber subscriber : subscribers.getOrDefault(conversationId, List.of())) {
                offer(subscriber, event);
            }
            return event;
        }
    }

    private void offer(Subscriber subscriber, ChatEvent event) {
        if (subscriber.closed || subscriber.dropped) {
            return;
        }
        if (!subscriber.queue.offer(event)) {
            // Dropped rather than waited for. The alternative is that a browser on a bad
            // connection decides how fast the agent runs.
            subscriber.dropped = true;
            LOG.info("Dropping a chat subscriber of {} that fell more than {} events behind;"
                            + " it can reconnect from sequence {}.",
                    Quoted.of(event.conversationId()), QUEUE, subscriber.lastSequence());
        }
    }

    /** Forgets a conversation's buffer and closes anyone still watching it. */
    public void forget(String conversationId) {
        synchronized (lockFor(conversationId)) {
            replay.remove(conversationId);
            sequences.remove(conversationId);
            List<Subscriber> watching = subscribers.remove(conversationId);
            if (watching != null) {
                watching.forEach(subscriber -> subscriber.closed = true);
            }
        }
        locks.remove(conversationId);
    }

    /** How many are watching a conversation, for a console that reports on itself. */
    public int watching(String conversationId) {
        return subscribers.getOrDefault(conversationId, List.of()).size();
    }
}
