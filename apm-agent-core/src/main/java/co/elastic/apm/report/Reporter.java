package co.elastic.apm.report;

import co.elastic.apm.impl.Process;
import co.elastic.apm.impl.Service;
import co.elastic.apm.impl.SystemInfo;
import co.elastic.apm.impl.Transaction;
import co.elastic.apm.util.ExecutorUtils;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.io.Closeable;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static co.elastic.apm.report.Reporter.ReportingEvent.ReportingEventType.FLUSH;
import static co.elastic.apm.report.Reporter.ReportingEvent.ReportingEventType.TRANSACTION;

public class Reporter implements Closeable {

    public static final int REPORTER_QUEUE_LENGTH = 1024;
    private static final int FLUSH_INTERVAL_SECONDS = 10;
    private static final EventTranslatorOneArg<ReportingEvent, Transaction> TRANSACTION_EVENT_TRANSLATOR = new EventTranslatorOneArg<ReportingEvent, Transaction>() {
        @Override
        public void translateTo(ReportingEvent event, long sequence, Transaction t) {
            event.transaction = t;
            event.type = TRANSACTION;
        }
    };
    private static final EventTranslator<ReportingEvent> FLUSH_EVENT_TRANSLATOR = new EventTranslator<ReportingEvent>() {
        @Override
        public void translateTo(ReportingEvent event, long sequence) {
            event.type = FLUSH;
        }
    };

    private final Disruptor<ReportingEvent> disruptor;
    private final ScheduledThreadPoolExecutor flushScheduler;
    private final AtomicInteger dropped = new AtomicInteger();
    private final boolean dropTransactionIfQueueFull;

    public Reporter(Service service, Process process, SystemInfo system, PayloadSender payloadSender, boolean dropTransactionIfQueueFull) {
        this.dropTransactionIfQueueFull = dropTransactionIfQueueFull;
        disruptor = new Disruptor<>(new TransactionEventFactory(), REPORTER_QUEUE_LENGTH, DaemonThreadFactory.INSTANCE);
        disruptor.handleEventsWith(new ReportingEventHandler(service, process, system, payloadSender));
        disruptor.start();
        flushScheduler = ExecutorUtils.createSingleThreadSchedulingDeamonPool("elastic-apm-transaction-flusher", 1);
        flushScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                scheduleFlush();
            }
        }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void report(Transaction transaction) {
        if (dropTransactionIfQueueFull) {
            boolean queueFull = !disruptor.getRingBuffer().tryPublishEvent(TRANSACTION_EVENT_TRANSLATOR, transaction);
            if (queueFull) {
                dropped.incrementAndGet();
                transaction.recycle();
            }
        } else {
            disruptor.getRingBuffer().publishEvent(TRANSACTION_EVENT_TRANSLATOR, transaction);
        }
    }

    public int getDropped() {
        return dropped.get();
    }

    public void scheduleFlush() {
        disruptor.publishEvent(FLUSH_EVENT_TRANSLATOR);
    }

    public void close() {
        disruptor.shutdown();
        flushScheduler.shutdown();
    }

    static class ReportingEvent {
        Transaction transaction;
        ReportingEventType type;

        public void setTransaction(Transaction transaction) {
            this.type = ReportingEventType.TRANSACTION;
            this.transaction = transaction;
        }

        public void resetState() {
            this.transaction = null;
            this.type = null;
        }

        enum ReportingEventType {
            FLUSH, TRANSACTION
        }
    }

    static class TransactionEventFactory implements EventFactory<ReportingEvent> {
        @Override
        public ReportingEvent newInstance() {
            return new ReportingEvent();
        }
    }
}