/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.grizzly;

import org.glassfish.grizzly.asyncqueue.WriteQueueMessage;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.streams.StreamReader;
import org.glassfish.grizzly.streams.StreamWriter;
import org.glassfish.grizzly.utils.EchoFilter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/**
 * AsyncWriteQueue tests.
 * 
 * @author Alexey Stashok
 * @author Ryan Lubke
 */
@RunWith(Parameterized.class)
public class AsyncWriteQueueTest {
    public static final int PORT = 7781;

    private static final Logger LOGGER = Grizzly.logger(AsyncWriteQueueTest.class);

    @Parameters
    public static Collection<Object[]> getOptimizedForMultiplexing() {
        return Arrays.asList(new Object[][]{
                    {Boolean.FALSE},
                    {Boolean.TRUE}
                });
    }
    
    private final boolean isOptimizedForMultiplexing;
    
    public AsyncWriteQueueTest(boolean isOptimizedForMultiplexing) {
        this.isOptimizedForMultiplexing = isOptimizedForMultiplexing;
    }
    
    private static TCPNIOTransport createTransport(
            final boolean isOptimizedForMultiplexing) {
        
        return TCPNIOTransportBuilder
                .newInstance()
                .setOptimizedForMultiplexing(isOptimizedForMultiplexing)
                .build();
    }
    
    @Test
    public void testAsyncWriteQueueEcho() throws Exception {
        Connection connection = null;
        StreamReader reader = null;
        StreamWriter writer = null;

        final int packetNumber = 127;
        final int packetSize = 128000;

        final AtomicInteger serverRcvdBytes = new AtomicInteger();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new EchoFilter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx)
                    throws IOException {
                serverRcvdBytes.addAndGet(((Buffer) ctx.getMessage()).remaining());
                return super.handleRead(ctx);
            }
        });

        TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(PORT);
            transport.start();

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            reader = ((StandaloneProcessor) connection.getProcessor()).getStreamReader(connection);

            final Writer<SocketAddress> asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            final CountDownLatch latch = new CountDownLatch(packetNumber);

            final CompletionHandler<WriteResult<WriteQueueMessage, SocketAddress>> completionHandler =
                    new EmptyCompletionHandler<WriteResult<WriteQueueMessage, SocketAddress>>() {
                @Override
                public void completed(WriteResult<WriteQueueMessage, SocketAddress> result) {
                    latch.countDown();
                }
            };

            Collection<Callable<Object>> sendTasks =
                    new ArrayList<Callable<Object>>(packetNumber + 1);
            for (int i = 0; i < packetNumber; i++) {
                final byte b = (byte) i;
                sendTasks.add(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        byte[] originalMessage = new byte[packetSize];
                        Arrays.fill(originalMessage, b);
                        Buffer buffer = Buffers.wrap(mm, originalMessage);
                        try {
                            asyncQueueWriter.write(con, buffer, completionHandler);
                        } catch (IOException e) {
                            assertTrue("IOException occurred", false);
                        }

                        return null;
                    }
                });
            }

            ExecutorService executorService = Executors.newFixedThreadPool(packetNumber / 10);
            try {
                executorService.invokeAll(sendTasks);
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    assertTrue("Send timeout!", false);
                }
            } finally {
                executorService.shutdown();
            }

            int responseSize = packetNumber * packetSize;
            Future<Integer> readFuture = reader.notifyAvailable(responseSize);
            Integer available = null;
            
            try {
                available = readFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "read error", e);
            }

            assertTrue("Read timeout. Server received: " +serverRcvdBytes.get() +
                    " bytes. Expected: " + packetNumber * packetSize,
                     available != null);

            byte[] echoMessage = new byte[responseSize];
            reader.readByteArray(echoMessage);

            // Check interleaving...

            boolean[] isByteUsed = new boolean[packetNumber];
            int offset = 0;
            for(int i=0; i<packetNumber; i++) {
                byte pattern = echoMessage[offset];

                assertEquals("Pattern: " + pattern + " was already used",
                        false, isByteUsed[pattern]);

                isByteUsed[pattern] = true;
                for(int j = 0; j < packetSize; j++) {
                    byte check = echoMessage[offset++];
                    assertEquals("Echo doesn't match. Offset: " + offset +
                            " pattern: " + pattern + " found: " + check,
                            pattern, check);
                }
            }

        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.stop();
        }
    }


    @Test
    public void testAsyncWriteQueueLimits() throws Exception {

        Connection connection = null;
        final int packetSize = 256000;
        final int queueLimit = packetSize * 2 + 1;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        

        TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());


        try {
            transport.bind(PORT);
            transport.start();

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);
            connection.configureStandalone(true);

            final AsyncQueueWriter<SocketAddress> asyncQueueWriter =
                    transport.getAsyncQueueIO().getWriter();
            asyncQueueWriter.setMaxPendingBytesPerConnection(queueLimit);
            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            final AtomicBoolean failed = new AtomicBoolean(false);

            transport.pause();
            
            int i = 0;
            int loopCount = 0;
            final AtomicBoolean exceptionThrown = new AtomicBoolean(false);
            final AtomicInteger exceptionAtLoopCount = new AtomicInteger();
            while (!failed.get() && loopCount < 4) {
                final int lc = loopCount;
                final byte b = (byte) i;
                byte[] originalMessage = new byte[packetSize];
                Arrays.fill(originalMessage, b);
                Buffer buffer = Buffers.wrap(mm, originalMessage);
                try {
                    if (asyncQueueWriter.canWrite(con, buffer.remaining())) {
                        asyncQueueWriter.write(con, buffer);
                    } else {
                        if (loopCount == 3) {
                            asyncQueueWriter.write(con, buffer,
                                    new EmptyCompletionHandler<WriteResult<WriteQueueMessage, SocketAddress>>() {

                                        @Override
                                        public void failed(Throwable throwable) {
                                            if (throwable instanceof PendingWriteQueueLimitExceededException) {
                                                exceptionThrown.compareAndSet(false, true);
                                                exceptionAtLoopCount.set(lc);
                                                assertTrue(((NIOConnection) con).getAsyncWriteQueue().spaceInBytes() + packetSize > queueLimit);
                                            }
                                            failed.compareAndSet(false, true);
                                        }
                                    });
                        } else {
                            loopCount++;
                            transport.resume();
                            Thread.sleep(5000);
                            transport.pause();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    assertTrue("IOException occurred: " + e.toString() + " loopCount=" + loopCount, false);

                }
                i++;
            }

            if (!exceptionThrown.get()) {
                fail("No Exception thrown when queue write limit exceeded");
            }
            if (exceptionAtLoopCount.get() != 3) {
                fail("Expected exception to occur at 4th iteration of test loop.  Occurred at: " + exceptionAtLoopCount);
            }

        } finally {
            if (connection != null) {
                connection.close();
            }
            if (transport.isPaused()) {
                transport.resume();
            }
            transport.stop();
        }
    }


    @Test
    public void testQueueNotification() throws Exception {

        Connection connection = null;
        final int packetSize = 256000;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());


        final TCPNIOTransport transport = createTransport(isOptimizedForMultiplexing);
        transport.setProcessor(filterChainBuilder.build());


        try {
            transport.bind(PORT);
            transport.start();

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);
            connection.configureStandalone(true);

            final AsyncQueueWriter asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            asyncQueueWriter.setMaxPendingBytesPerConnection(256000 * 10);
            System.out.println("Max Space: " + asyncQueueWriter.getMaxPendingBytesPerConnection());
            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            transport.pause();
            final TaskQueue tqueue = ((NIOConnection) connection).getAsyncWriteQueue();

            do {
                byte[] originalMessage = new byte[packetSize];
                Arrays.fill(originalMessage, (byte) 1);
                Buffer buffer = Buffers.wrap(mm, originalMessage);
                try {
                    if (asyncQueueWriter.canWrite(con, 256000)) {
                        asyncQueueWriter.write(con, buffer);
                    }
                } catch (IOException e) {
                    assertTrue("IOException occurred: " + e.toString(), false);
                }
            } while (asyncQueueWriter.canWrite(con, 256000));  // fill the buffer

            // out of space.  Add a monitor to be notified when space is available
            tqueue.setQueueMonitor(new WriteQueueFreeSpaceMonitor(con, 256000 * 4));

            transport.resume(); // resume the transport so bytes start draining from the queue

            long start = 0;
            try {
                System.out.println("Waiting for free space notification.  Max wait time is 10000ms.");
                start = System.currentTimeMillis();
                Thread.sleep(10000);  // should be interrupted before time completes
                fail("Thread not interrupted within 10 seconds.");
            } catch (InterruptedException ie) {
                long result = (System.currentTimeMillis() - start);
                System.out.println("Notified in " + result + "ms");
            }
            assertTrue((asyncQueueWriter.getMaxPendingBytesPerConnection() - tqueue.spaceInBytes()) >= (256000 * 4)); 
            System.out.println("Queue Space: " + tqueue.spaceInBytes());

        } finally {
            if (connection != null) {
                connection.close();
            }
            if (transport.isPaused()) {
                transport.resume();
            }
            transport.stop();
        }
    }

    @Test
    public void testAsyncWriteQueueReentrants() throws Exception {
        Connection connection = null;

        final AtomicInteger serverRcvdBytes = new AtomicInteger();

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new BaseFilter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx)
                    throws IOException {
                serverRcvdBytes.addAndGet(((Buffer) ctx.getMessage()).remaining());
                return ctx.getStopAction();
            }
        });

        // reentrants limitation is applicable for non-multiplexing-optimized write queue
        TCPNIOTransport transport = createTransport(false);
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(PORT);
            transport.start();

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            final AsyncQueueWriter<SocketAddress> asyncQueueWriter = transport.getAsyncQueueIO().getWriter();
            
            final MemoryManager mm = transport.getMemoryManager();
            final Connection con = connection;

            final int maxReentrants = 10;
            asyncQueueWriter.setMaxWriteReentrants(10);

            final AtomicInteger packetCounter = new AtomicInteger();

            final FutureImpl<Boolean> resultFuture = SafeFutureImpl.<Boolean>create();
            final Queue<Thread> threadsHistory = new ConcurrentLinkedQueue<Thread>();

            final Thread currentThread = Thread.currentThread();
            threadsHistory.add(currentThread);
            
            Buffer buffer = Buffers.wrap(mm, "" + ((char) ('A' + packetCounter.getAndIncrement())));

            asyncQueueWriter.write(con, buffer,
                    new EmptyCompletionHandler<WriteResult<WriteQueueMessage, SocketAddress>>() {

                        @Override
                        public void completed(
                                WriteResult<WriteQueueMessage, SocketAddress> result) {

                            final int packetNum = packetCounter.incrementAndGet();
                            if (packetNum <= maxReentrants + 1) {
                                threadsHistory.add(Thread.currentThread());
                                Buffer bufferInner = Buffers.wrap(mm, "" +
                                        ((char) ('A' + packetNum)));
                                try {
                                    asyncQueueWriter.write(con, bufferInner, this);
                                } catch (IOException e) {
                                    resultFuture.failure(e);
                                }
                            } else {
                                resultFuture.result(Boolean.TRUE);
                            }
                        }
                    });

            assertTrue(resultFuture.get(10, TimeUnit.SECONDS));

            int counter = 0;
            while (!threadsHistory.isEmpty()) {
                final Thread t = threadsHistory.poll();
                if (!threadsHistory.isEmpty()) {
                    // not last thread in history (should be main/current)
                    assertSame("counter=" + counter, currentThread, t);
                } else {
                    // the last thread in history (should *not* be main/current)
                    assertNotSame("counter=" + counter, currentThread, t);
                }
                counter++;
            }
        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.stop();
        }
    }
    
    // ---------------------------------------------------------- Nested Classes


    private static class WriteQueueFreeSpaceMonitor extends TaskQueue.QueueMonitor {

        private final TaskQueue writeQueue;
        private final int freeSpaceAvailable;
        private final int maxSpace;
        private final Transport transport;
        private final Thread current;


        // -------------------------------------------------------- Constructors


        public WriteQueueFreeSpaceMonitor(final Connection c,
                                          final int freeSpaceAvailable) {
            this.freeSpaceAvailable = freeSpaceAvailable;
            writeQueue = ((NIOConnection) c).getAsyncWriteQueue();
            transport = c.getTransport();
            maxSpace = (((TCPNIOTransport) transport).getAsyncQueueIO().getWriter().getMaxPendingBytesPerConnection());
            current = Thread.currentThread();
        }


        // -------------------------------------- Methods from QueueMonitor

        @Override
        public boolean shouldNotify() {
            return ((maxSpace - writeQueue.spaceInBytes()) > freeSpaceAvailable);
        }

        @Override
        public void onNotify() {
            try {
                transport.pause(); // prevent more writes
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
            current.interrupt(); // wake up the test thread
        }

    } // END WriteQueueFreeSpaceMonitor
}