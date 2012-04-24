/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.LocalLog;
import android.util.Slog;

import com.google.android.collect.Lists;

import java.nio.charset.Charsets;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedList;

/**
 * Generic connector class for interfacing with a native daemon which uses the
 * {@code libsysutils} FrameworkListener protocol.
 */
final class NativeDaemonConnector implements Runnable, Handler.Callback, Watchdog.Monitor {
    private static final boolean LOGD = false;

    private final String TAG;

    private String mSocket;
    private OutputStream mOutputStream;
    private LocalLog mLocalLog;

    private final ResponseQueue mResponseQueue;

    private INativeDaemonConnectorCallbacks mCallbacks;
    private Handler mCallbackHandler;

    private AtomicInteger mSequenceNumber;

    private static final int DEFAULT_TIMEOUT = 1 * 60 * 1000; /* 1 minute */

    /** Lock held whenever communicating with native daemon. */
    private final Object mDaemonLock = new Object();

    private final int BUFFER_SIZE = 4096;

    NativeDaemonConnector(INativeDaemonConnectorCallbacks callbacks, String socket,
            int responseQueueSize, String logTag, int maxLogSize) {
        mCallbacks = callbacks;
        mSocket = socket;
        mResponseQueue = new ResponseQueue(responseQueueSize);
        mSequenceNumber = new AtomicInteger(0);
        TAG = logTag != null ? logTag : "NativeDaemonConnector";
        mLocalLog = new LocalLog(maxLogSize);
    }

    @Override
    public void run() {
        HandlerThread thread = new HandlerThread(TAG + ".CallbackHandler");
        thread.start();
        mCallbackHandler = new Handler(thread.getLooper(), this);

        while (true) {
            try {
                listenToSocket();
            } catch (Exception e) {
                loge("Error in NativeDaemonConnector: " + e);
                SystemClock.sleep(5000);
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        String event = (String) msg.obj;
        try {
            if (!mCallbacks.onEvent(msg.what, event, NativeDaemonEvent.unescapeArgs(event))) {
                log(String.format("Unhandled event '%s'", event));
            }
        } catch (Exception e) {
            loge("Error handling '" + event + "': " + e);
        }
        return true;
    }

    private void listenToSocket() throws IOException {
        LocalSocket socket = null;

        try {
            socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(mSocket,
                    LocalSocketAddress.Namespace.RESERVED);

            socket.connect(address);

            InputStream inputStream = socket.getInputStream();
            synchronized (mDaemonLock) {
                mOutputStream = socket.getOutputStream();
            }

            mCallbacks.onDaemonConnected();

            byte[] buffer = new byte[BUFFER_SIZE];
            int start = 0;

            while (true) {
                int count = inputStream.read(buffer, start, BUFFER_SIZE - start);
                if (count < 0) {
                    loge("got " + count + " reading with start = " + start);
                    break;
                }

                // Add our starting point to the count and reset the start.
                count += start;
                start = 0;

                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        final String rawEvent = new String(
                                buffer, start, i - start, Charsets.UTF_8);
                        log("RCV <- {" + rawEvent + "}");

                        try {
                            final NativeDaemonEvent event = NativeDaemonEvent.parseRawEvent(
                                    rawEvent);
                            if (event.isClassUnsolicited()) {
                                // TODO: migrate to sending NativeDaemonEvent instances
                                mCallbackHandler.sendMessage(mCallbackHandler.obtainMessage(
                                        event.getCode(), event.getRawEvent()));
                            } else {
                                mResponseQueue.add(event.getCmdNumber(), event);
                            }
                        } catch (IllegalArgumentException e) {
                            log("Problem parsing message: " + rawEvent + " - " + e);
                        }

                        start = i + 1;
                    }
                }
                if (start == 0) {
                    final String rawEvent = new String(buffer, start, count, Charsets.UTF_8);
                    log("RCV incomplete <- {" + rawEvent + "}");
                }

                // We should end at the amount we read. If not, compact then
                // buffer and read again.
                if (start != count) {
                    final int remaining = BUFFER_SIZE - start;
                    System.arraycopy(buffer, start, buffer, 0, remaining);
                    start = remaining;
                } else {
                    start = 0;
                }
            }
        } catch (IOException ex) {
            loge("Communications error: " + ex);
            throw ex;
        } finally {
            synchronized (mDaemonLock) {
                if (mOutputStream != null) {
                    try {
                        loge("closing stream for " + mSocket);
                        mOutputStream.close();
                    } catch (IOException e) {
                        loge("Failed closing output stream: " + e);
                    }
                    mOutputStream = null;
                }
            }

            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ex) {
                loge("Failed closing socket: " + ex);
            }
        }
    }

    /**
     * Make command for daemon, escaping arguments as needed.
     */
    private void makeCommand(StringBuilder builder, String cmd, Object... args)
            throws NativeDaemonConnectorException {
        // TODO: eventually enforce that cmd doesn't contain arguments
        if (cmd.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("unexpected command: " + cmd);
        }

        builder.append(cmd);
        for (Object arg : args) {
            final String argString = String.valueOf(arg);
            if (argString.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }

            builder.append(' ');
            appendEscaped(builder, argString);
        }
    }

    /**
     * Issue the given command to the native daemon and return a single expected
     * response.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent execute(Command cmd) throws NativeDaemonConnectorException {
        return execute(cmd.mCmd, cmd.mArguments.toArray());
    }

    /**
     * Issue the given command to the native daemon and return a single expected
     * response.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent execute(String cmd, Object... args)
            throws NativeDaemonConnectorException {
        final NativeDaemonEvent[] events = executeForList(cmd, args);
        if (events.length != 1) {
            throw new NativeDaemonConnectorException(
                    "Expected exactly one response, but received " + events.length);
        }
        return events[0];
    }

    /**
     * Issue the given command to the native daemon and return any
     * {@link NativeDaemonEvent#isClassContinue()} responses, including the
     * final terminal response.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent[] executeForList(Command cmd) throws NativeDaemonConnectorException {
        return executeForList(cmd.mCmd, cmd.mArguments.toArray());
    }

    /**
     * Issue the given command to the native daemon and return any
     * {@link NativeDaemonEvent#isClassContinue()} responses, including the
     * final terminal response.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent[] executeForList(String cmd, Object... args)
            throws NativeDaemonConnectorException {
            return execute(DEFAULT_TIMEOUT, cmd, args);
    }

    /**
     * Issue the given command to the native daemon and return any
     * {@linke NativeDaemonEvent@isClassContinue()} responses, including the
     * final terminal response.  Note that the timeout does not count time in
     * deep sleep.
     *
     * @throws NativeDaemonConnectorException when problem communicating with
     *             native daemon, or if the response matches
     *             {@link NativeDaemonEvent#isClassClientError()} or
     *             {@link NativeDaemonEvent#isClassServerError()}.
     */
    public NativeDaemonEvent[] execute(int timeout, String cmd, Object... args)
            throws NativeDaemonConnectorException {
        final ArrayList<NativeDaemonEvent> events = Lists.newArrayList();

        final int sequenceNumber = mSequenceNumber.incrementAndGet();
        final StringBuilder cmdBuilder =
                new StringBuilder(Integer.toString(sequenceNumber)).append(' ');

        makeCommand(cmdBuilder, cmd, args);

        final String logCmd = cmdBuilder.toString(); /* includes cmdNum, cmd, args */
        log("SND -> {" + logCmd + "}");

        cmdBuilder.append('\0');
        final String sentCmd = cmdBuilder.toString(); /* logCmd + \0 */

        synchronized (mDaemonLock) {
            if (mOutputStream == null) {
                throw new NativeDaemonConnectorException("missing output stream");
            } else {
                try {
                    mOutputStream.write(sentCmd.getBytes(Charsets.UTF_8));
                } catch (IOException e) {
                    throw new NativeDaemonConnectorException("problem sending command", e);
                }
            }
        }

        NativeDaemonEvent event = null;
        do {
            event = mResponseQueue.remove(sequenceNumber, timeout, sentCmd);
            if (event == null) {
                loge("timed-out waiting for response to " + logCmd);
                throw new NativeDaemonFailureException(logCmd, event);
            }
            events.add(event);
        } while (event.isClassContinue());

        if (event.isClassClientError()) {
            throw new NativeDaemonArgumentException(logCmd, event);
        }
        if (event.isClassServerError()) {
            throw new NativeDaemonFailureException(logCmd, event);
        }

        return events.toArray(new NativeDaemonEvent[events.size()]);
    }

    /**
     * Issue a command to the native daemon and return the raw responses.
     *
     * @deprecated callers should move to {@link #execute(String, Object...)}
     *             which returns parsed {@link NativeDaemonEvent}.
     */
    @Deprecated
    public ArrayList<String> doCommand(String cmd) throws NativeDaemonConnectorException {
        final ArrayList<String> rawEvents = Lists.newArrayList();
        final NativeDaemonEvent[] events = executeForList(cmd);
        for (NativeDaemonEvent event : events) {
            rawEvents.add(event.getRawEvent());
        }
        return rawEvents;
    }

    /**
     * Issues a list command and returns the cooked list of all
     * {@link NativeDaemonEvent#getMessage()} which match requested code.
     */
    @Deprecated
    public String[] doListCommand(String cmd, int expectedCode)
            throws NativeDaemonConnectorException {
        final ArrayList<String> list = Lists.newArrayList();

        final NativeDaemonEvent[] events = executeForList(cmd);
        for (int i = 0; i < events.length - 1; i++) {
            final NativeDaemonEvent event = events[i];
            final int code = event.getCode();
            if (code == expectedCode) {
                list.add(event.getMessage());
            } else {
                throw new NativeDaemonConnectorException(
                        "unexpected list response " + code + " instead of " + expectedCode);
            }
        }

        final NativeDaemonEvent finalEvent = events[events.length - 1];
        if (!finalEvent.isClassOk()) {
            throw new NativeDaemonConnectorException("unexpected final event: " + finalEvent);
        }

        return list.toArray(new String[list.size()]);
    }

    /**
     * Append the given argument to {@link StringBuilder}, escaping as needed,
     * and surrounding with quotes when it contains spaces.
     */
    // @VisibleForTesting
    static void appendEscaped(StringBuilder builder, String arg) {
        final boolean hasSpaces = arg.indexOf(' ') >= 0;
        if (hasSpaces) {
            builder.append('"');
        }

        final int length = arg.length();
        for (int i = 0; i < length; i++) {
            final char c = arg.charAt(i);

            if (c == '"') {
                builder.append("\\\"");
            } else if (c == '\\') {
                builder.append("\\\\");
            } else {
                builder.append(c);
            }
        }

        if (hasSpaces) {
            builder.append('"');
        }
    }

    private static class NativeDaemonArgumentException extends NativeDaemonConnectorException {
        public NativeDaemonArgumentException(String command, NativeDaemonEvent event) {
            super(command, event);
        }

        @Override
        public IllegalArgumentException rethrowAsParcelableException() {
            throw new IllegalArgumentException(getMessage(), this);
        }
    }

    private static class NativeDaemonFailureException extends NativeDaemonConnectorException {
        public NativeDaemonFailureException(String command, NativeDaemonEvent event) {
            super(command, event);
        }
    }

    /**
     * Command builder that handles argument list building.
     */
    public static class Command {
        private String mCmd;
        private ArrayList<Object> mArguments = Lists.newArrayList();

        public Command(String cmd, Object... args) {
            mCmd = cmd;
            for (Object arg : args) {
                appendArg(arg);
            }
        }

        public Command appendArg(Object arg) {
            mArguments.add(arg);
            return this;
        }
    }

    /** {@inheritDoc} */
    public void monitor() {
        synchronized (mDaemonLock) { }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mLocalLog.dump(fd, pw, args);
        pw.println();
        mResponseQueue.dump(fd, pw, args);
    }

    private void log(String logstring) {
        if (LOGD) Slog.d(TAG, logstring);
        mLocalLog.log(logstring);
    }

    private void loge(String logstring) {
        Slog.e(TAG, logstring);
        mLocalLog.log(logstring);
    }

    private static class ResponseQueue {

        private static class Response {
            public int cmdNum;
            public LinkedList<NativeDaemonEvent> responses = new LinkedList<NativeDaemonEvent>();
            public String request;
            public Response(int c, String r) {cmdNum = c; request = r;}
        }

        private final LinkedList<Response> mResponses;
        private int mMaxCount;

        ResponseQueue(int maxCount) {
            mResponses = new LinkedList<Response>();
            mMaxCount = maxCount;
        }

        public void add(int cmdNum, NativeDaemonEvent response) {
            Response found = null;
            synchronized (mResponses) {
                for (Response r : mResponses) {
                    if (r.cmdNum == cmdNum) {
                        found = r;
                        break;
                    }
                }
                if (found == null) {
                    // didn't find it - make sure our queue isn't too big before adding
                    // another..
                    while (mResponses.size() >= mMaxCount) {
                        Slog.e("NativeDaemonConnector.ResponseQueue",
                                "more buffered than allowed: " + mResponses.size() +
                                " >= " + mMaxCount);
                        // let any waiter timeout waiting for this
                        Response r = mResponses.remove();
                        Slog.e("NativeDaemonConnector.ResponseQueue",
                                "Removing request: " + r.request + " (" + r.cmdNum + ")");
                    }
                    found = new Response(cmdNum, null);
                    mResponses.add(found);
                }
                found.responses.add(response);
            }
            synchronized (found) {
                found.notify();
            }
        }

        // note that the timeout does not count time in deep sleep.  If you don't want
        // the device to sleep, hold a wakelock
        public NativeDaemonEvent remove(int cmdNum, int timeoutMs, String origCmd) {
            long endTime = SystemClock.uptimeMillis() + timeoutMs;
            long nowTime;
            Response found = null;
            while (true) {
                synchronized (mResponses) {
                    for (Response response : mResponses) {
                        if (response.cmdNum == cmdNum) {
                            found = response;
                            // how many response fragments are left
                            switch (response.responses.size()) {
                            case 0:  // haven't got any - must wait
                                break;
                            case 1:  // last one - remove this from the master list
                                mResponses.remove(response); // fall through
                            default: // take one and move on
                                response.request = origCmd;
                                return response.responses.remove();
                            }
                        }
                    }
                    nowTime = SystemClock.uptimeMillis();
                    if (endTime <= nowTime) {
                        Slog.e("NativeDaemonConnector.ResponseQueue",
                                "Timeout waiting for response");
                        return null;
                    }
                    /* pre-allocate so we have something unique to wait on */
                    if (found == null) {
                        found = new Response(cmdNum, origCmd);
                        mResponses.add(found);
                    }
                }
                try {
                    synchronized (found) {
                        found.wait(endTime - nowTime);
                    }
                } catch (InterruptedException e) {
                    // loop around to check if we're done or if it's time to stop waiting
                }
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("Pending requests:");
            synchronized (mResponses) {
                for (Response response : mResponses) {
                    pw.println("  Cmd " + response.cmdNum + " - " + response.request);
                }
            }
        }
    }
}
