package loghub.senders;

import java.io.Closeable;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;

import loghub.AbstractBuilder;
import loghub.CanBatch;
import loghub.Event;
import loghub.Filter;
import loghub.FilterException;
import loghub.Helpers;
import loghub.Stats;
import loghub.ThreadBuilder;
import loghub.configuration.Properties;
import loghub.encoders.EncodeException;
import loghub.encoders.Encoder;
import lombok.Getter;
import lombok.Setter;

public abstract class Sender extends Thread implements Closeable {

    protected static class Batch extends ArrayList<EventFuture> {
        private final Counter counter;
        private final Sender sender;
        Batch() {
            super(0);
            this.sender = null;
            counter = null;
        }
        Batch(Sender sender) {
            super(sender.batchSize);
            this.sender = sender;
            counter = Properties.metrics.counter("sender." + sender.getName() + ".activeBatches");
            counter.inc();
        }
        void finished() {
            super.stream().forEach(sender::processStatus);
            counter.dec();
        }
        public EventFuture add(Event e) {
            EventFuture fe = new EventFuture(e);
            add(fe);
            return fe;
        }
        @Override
        public Stream<EventFuture> stream() {
            return super.stream().filter(EventFuture::isNotDone);
        }
        @Override
        public void forEach(Consumer<? super EventFuture> action) {
            stream().forEach(action);
        }
    }

    // A marker to end processing
    static private final Batch NULLBATCH = new Batch();

    static public class EventFuture extends CompletableFuture<Boolean> {
        @Getter
        private final Event event;
        @Getter
        private String message;
        public EventFuture(Event ev) {
            this.event = ev;
        }
        public EventFuture(Event ev, boolean status) {
            this.event = ev;
            this.complete(status);
        }
        public void failure(String message) {
            this.complete(false);
            this.message = message;
        }
        public boolean isNotDone() {
            return ! isDone();
        }
    }

    public abstract static class Builder<B extends Sender> extends AbstractBuilder<B> {
        @Setter
        protected Encoder encoder;
        @Setter
        protected int batchSize = -1;
        @Setter
        protected int workers = 2;
        @Setter
        protected int flushInterval = 5;
        @Setter
        private Filter filter;
    };

    protected final Logger logger;

    private BlockingQueue<Event> inQueue;
    @Getter
    private final Encoder encoder;
    private final boolean isAsync;

    // Batch settings
    @Getter
    private final int batchSize;
    @Getter
    private final Filter filter;
    private volatile long lastFlush = 0;
    private final Thread[] threads;
    private final BlockingQueue<Batch> batches;
    private final Runnable publisher;
    private final AtomicReference<Batch> batch = new AtomicReference<>();
    private final int flushInterval;
    private volatile boolean closed = false;
    // Don't allow to stop while sending an event
    private final Semaphore stopSemaphore = new Semaphore(1, true);

    public Sender(Builder<?  extends  Sender> builder) {
        filter = builder.filter;
        setDaemon(true);
        setName("sender-" + getSenderName());
        logger = LogManager.getLogger(Helpers.getFirstInitClass());
        encoder = builder.encoder;
        boolean onlyBatch = Optional.ofNullable(getClass().getAnnotation(CanBatch.class)).map(CanBatch::only).orElse(false);
        if (onlyBatch) {
            builder.batchSize = Math.max(1, builder.batchSize);
            builder.workers = Math.max(1, builder.workers);
        }
        if (builder.batchSize > 0 && getClass().getAnnotation(CanBatch.class) != null) {
            flushInterval = builder.flushInterval * 1000;
            isAsync = true;
            batchSize = builder.batchSize;
            threads = new Thread[builder.workers];
            batches = new ArrayBlockingQueue<>(threads.length * 8);
            publisher = getPublisher();
            batch.set(new Batch(this));
        } else {
            flushInterval = 0;
            isAsync = getClass().getAnnotation(AsyncSender.class) != null;
            threads = null;
            batchSize = -1;
            batches = null;
            publisher = null;
        }
        this.setUncaughtExceptionHandler((t,e) -> {
            logger.error("Uncatched Exception: " + Helpers.resolveThrowableException(e), e);
        });
    }

    public boolean configure(Properties properties) {
        if (threads != null) {
            buildSyncer(properties);
        }
        if (encoder != null) {
            return encoder.configure(properties, this);
        } else if (getClass().getAnnotation(SelfEncoder.class) == null) {
            logger.error("Missing encoder");
            return false;
        } else {
            return true;
        }
    }

    /**
     * A runnable that will be affected to threads. It consumes event and send them as bulk
     * @return
     */
    protected Runnable getPublisher() {
        return () -> {
            try {
                while (true) {
                    Batch flushedBatch = batches.take();
                    if (flushedBatch == NULLBATCH) {
                        break;
                    }
                    Properties.metrics.histogram("sender." + getName() + ".batchesSize").update(flushedBatch.size());
                    if (flushedBatch.isEmpty()) {
                        flushedBatch.finished();
                        continue;
                    } else {
                        lastFlush = System.currentTimeMillis();
                    }
                    try (Timer.Context tctx = Properties.metrics.timer("sender." + getName() + ".flushDuration").time()) {
                        flush(flushedBatch);
                        flushedBatch.forEach(fe -> fe.complete(true));
                    } catch (Throwable ex) {
                        Sender.this.handleException(ex);
                        flushedBatch.forEach(fe -> fe.complete(false));
                    } finally {
                        flushedBatch.finished();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    protected void buildSyncer(Properties properties) {
        IntStream.rangeClosed(1, threads.length)
                 .mapToObj(i ->getName() + "Publisher" + i)
                 .map(i -> ThreadBuilder.get().setName(i))
                 .map(i -> i.setTask(publisher))
                 .map(i -> i.setDaemon(false))
                 .map(i -> i.build(true))
                 .toArray(i -> threads);
        //Schedule a task to flush every 5 seconds
        Runnable flush = () -> {
            try {
                long now =  System.currentTimeMillis();
                if ((now - lastFlush) > flushInterval) {
                    batches.add(batch.getAndSet(new Batch(this)));
                }
            } catch (IllegalStateException e) {
                logger.warn("Failed to launch a scheduled batch: " + Helpers.resolveThrowableException(e));
                logger.catching(Level.DEBUG, e);
            }
        };
        properties.registerScheduledTask(getName() + "Flusher" , flush, 5000);
        Helpers.waitAllThreads(Arrays.stream(threads));
    }

    public final synchronized void stopSending() {
        boolean locked = false;
        try {
            stopSemaphore.acquire();
            locked = true;
            closed = true;
            if (isWithBatch()) {
                List<Batch> missedBatches = new ArrayList<>();
                // Empty the waiting batches list and put the end-of-processing mark instead
                batches.drainTo(missedBatches);
                // Add a mark for each worker
                for (int i = 0; i < this.threads.length; i++) {
                    batches.add(NULLBATCH);
                }
                // Mark all waiting events as missed
                batch.get().forEach(ef -> ef.complete(false));
                missedBatches.forEach(b -> b.forEach(ef -> ef.complete(false)));
                // Wait for all publisher threads to be finished
                Arrays.stream(threads).forEach(t -> {
                    try {
                        t.join(1000);
                        t.interrupt();
                    } catch (InterruptedException e) {
                        interrupt();
                    }
                });
            }
            try {
                MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                mbs.unregisterMBean(new ObjectName("loghub:type=sender,servicename="
                                                   + getName()
                                                   + ",name=connectionsPool"));
            } catch (InstanceNotFoundException e) {
                logger.debug("Failed to unregister mbeam: "
                             + Helpers.resolveThrowableException(e), e);
            } catch (MalformedObjectNameException
                            | MBeanRegistrationException e) {
                logger.error("Failed to unregister mbeam: "
                             + Helpers.resolveThrowableException(e), e);
                logger.catching(Level.DEBUG, e);
            }
            customStopSending();
            interrupt();
        } catch (InterruptedException ex) {
            logger.error("Failed to stop collect: "
                            + Helpers.resolveThrowableException(ex), ex);
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                stopSemaphore.release();
            }
            try {
                join();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
    
    protected void customStopSending() {
        // Empty
    }

    protected abstract boolean send(Event e) throws SendException, EncodeException;

    protected boolean queue(Event event) {
        if (closed) {
            return false;
        }
        batch.get().add(event);
        if (batch.get().size() >= batchSize) {
            logger.debug("batch full, flush");
            try {
                batches.put(batch.getAndSet(new Batch(this)));
            } catch (InterruptedException e) {
                interrupt();
            }
            if (batches.size() > threads.length) {
                logger.warn("{} waiting flush batches, add workers", () -> batches.size() - threads.length);
            }
        }
        return true;
    }

    public abstract String getSenderName();

    protected void flush(Batch documents) throws SendException, EncodeException {
        throw new UnsupportedOperationException("Not a batching sender");
    }

    @FunctionalInterface
    private interface ByteSource {
        byte[] get() throws EncodeException;
    }

    protected byte[] encode(Batch documents) throws EncodeException {
        return genericEncoder(() -> encoder.encode(documents.stream().map(ef -> ef.event)));
    }

    protected byte[] encode(Event event) throws EncodeException {
        return genericEncoder(() -> encoder.encode(event));
    }

    private byte[] genericEncoder(ByteSource bs) throws EncodeException {
        if (filter != null) {
            try {
                return filter.filter(bs.get());
            } catch (FilterException e) {
                throw new EncodeException(e);
            }
        } else {
            return bs.get();
        }
    }

    public void run() {
        while (isRunning()) {
            Event event = null;
            try {
                event = inQueue.take();
            } catch (InterruptedException e) {
                interrupt();
                break;
            }
            try {
                stopSemaphore.acquire();
                logger.trace("New event to send: {}", event);
                boolean status = isWithBatch() ? queue(event): send(event);
                if (! isAsync) {
                    // real async or in batch mode
                    processStatus(event, status);
                } else if (isWithBatch() && ! status) {
                    // queue return false if this event was not batched
                    processStatus(event, status);
                }
                event = null;
            } catch (InterruptedException e) {
                interrupt();
                break;
            } catch (Throwable t) {
                handleException(t);
                processStatus(event, false);
            } finally {
                stopSemaphore.release();
            }
        }
    }

    protected boolean isRunning() {
        return !closed && ! isInterrupted();
    }

    public boolean isWithBatch() {
        return threads != null;
    }
    
    public int getWorkers() {
        return threads != null ? threads.length : 0;
    }

    /**
     * A method that can be used inside custom {@link Sender#run()} for synchronous wait
     * 
     * @return a waiting event
     * @throws InterruptedException
     */
    protected Event getNext() throws InterruptedException {
        return inQueue.take();
    }

    protected void processStatus(EventFuture result) {
        try {
            if (result.get()) {
                Stats.sent.incrementAndGet();
            } else {
                String message = result.getMessage();
                if (message != null) {
                    Stats.newSenderError(message);
                } else {
                    Stats.failedSend.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            interrupt();
        } catch (ExecutionException e) {
            handleException(e.getCause());
        }
        result.event.end();
    }

    protected void handleException(Throwable t) {
        try {
            throw t;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SendException e) {
            Stats.newSenderError(Helpers.resolveThrowableException(e));
            logger.error("Sending exception: {}", Helpers.resolveThrowableException(e));
            logger.catching(Level.DEBUG, e);
        } catch (EncodeException e) {
            Stats.newSenderError(Helpers.resolveThrowableException(e));
            logger.error("Sending exception: {}", Helpers.resolveThrowableException(e));
            logger.catching(Level.DEBUG, e);
        } catch (Error e) {
            if (Helpers.isFatal(e)) {
                throw e;
            } else {
                String message = Helpers.resolveThrowableException(e);
                Stats.newUnhandledException(e);
                logger.error("Unexpected exception: {}", message);
                logger.catching(Level.ERROR, e);
            }
        } catch (Throwable e) {
            String message = Helpers.resolveThrowableException(e);
            Stats.newUnhandledException(e);
            logger.error("Unexpected exception: {}", message);
            logger.catching(Level.ERROR, e);
        }
    }

    protected void processStatus(Event event, boolean status) {
        if (status) {
            Stats.sent.incrementAndGet();
        } else {
            Stats.failedSend.incrementAndGet();
        }
        event.end();
    }

    public void setInQueue(BlockingQueue<Event> inQueue) {
        this.inQueue = inQueue;
    }

    @Override
    public void close() {
        logger.debug("Closing");
        stopSending();
    }

    public int getThreads() {
        return threads != null ? threads.length : 0;
    }

}
