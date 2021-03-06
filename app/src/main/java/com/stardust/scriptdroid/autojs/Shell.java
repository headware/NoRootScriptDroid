package com.stardust.scriptdroid.autojs;

import android.content.Context;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

import com.stardust.autojs.runtime.ScriptInterruptedException;
import com.stardust.autojs.runtime.api.AbstractShell;
import com.stardust.lang.ThreadCompat;
import com.stardust.pio.UncheckedIOException;
import com.stardust.scriptdroid.App;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import jackpal.androidterm.ShellTermSession;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.util.TermSettings;

/**
 * Created by Stardust on 2017/4/24.
 */

public class Shell extends AbstractShell implements AutoCloseable {

    public interface Callback {
        void onNewLine(String str);

        void onInitialized();

        void onInterrupted(InterruptedException e);
    }

    private static final String TAG = "Shell";

    private TermSession mTermSession;
    private final Object mInitLock = new Object();
    private final Object mExitLock = new Object();
    private volatile RuntimeException mInitException;
    private volatile boolean mInitialized = false;
    private volatile boolean mWaitingExit = false;
    private Callback mCallback;

    public Shell() {
        super();
    }

    public Shell(boolean root) {
        super(root);
    }

    @Override
    protected void init(String initialCommand) {
        init(initialCommand, App.getApp(), AutoJs.getInstance().getUiHandler());
    }

    private void init(final String initialCommand, final Context context, Handler uiHandler) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                TermSettings settings = new TermSettings(context.getResources(), PreferenceManager.getDefaultSharedPreferences(context));
                try {
                    mTermSession = new MyShellTermSession(settings, initialCommand);
                    mTermSession.initializeEmulator(40, 40);
                } catch (IOException e) {
                    mInitException = new UncheckedIOException(e);
                }
            }
        });
    }

    public void exec(String command) {
        ensureInitialized();
        mTermSession.write(command + "\n");
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    private void ensureInitialized() {
        if (mTermSession == null) {
            checkInitException();
            waitInitialization();
            if (mTermSession == null) {
                checkInitException();
                throw new IllegalStateException();
            }
        }
    }

    private void checkInitException() {
        if (mInitException != null) {
            throw mInitException;
        }
    }

    private void waitInitialization() {
        if (mInitialized)
            throw new IllegalStateException("already initialized");
        synchronized (mInitLock) {
            try {
                mInitLock.wait();
            } catch (InterruptedException e) {
                onInterrupted(e);
            }
        }
    }

    private void onInterrupted(InterruptedException e) {
        if (mCallback == null) {
            exit();
            throw new ScriptInterruptedException();
        } else {
            mCallback.onInterrupted(e);
        }
    }

    @Override
    public void exit() {
        mTermSession.finish();
    }

    @Override
    public void exitAndWaitFor() {
        execExitAndWait();
        if (!isRoot()) {
            return;
        }
        execExitAndWait();
    }

    private void execExitAndWait() {
        synchronized (mExitLock) {
            mWaitingExit = true;
            exec("exit");
            try {
                mExitLock.wait();
            } catch (InterruptedException e) {
                onInterrupted(e);
            }
        }
    }

    @Override
    public void close() {
        exit();
    }

    private class MyShellTermSession extends ShellTermSession {

        private BufferedReader mBufferedReader;
        private OutputStream mOutputStream;
        private Thread mReadingThread;

        public MyShellTermSession(TermSettings settings, String initialCommand) throws IOException {
            super(settings, initialCommand);
            PipedInputStream pipedInputStream = new PipedInputStream(8192);
            mBufferedReader = new BufferedReader(new InputStreamReader(pipedInputStream));
            mOutputStream = new PipedOutputStream(pipedInputStream);
            startReadingThread();
        }

        private void startReadingThread() {
            mReadingThread = new ThreadCompat(new Runnable() {
                @Override
                public void run() {
                    String line;
                    try {
                        while (!Thread.currentThread().isInterrupted()
                                && (line = mBufferedReader.readLine()) != null) {
                            onNewLine(line);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            mReadingThread.start();
        }

        private void onNewLine(String line) {
            Log.d(TAG, line);
            if (mInitialized) {
                if (mCallback != null) {
                    mCallback.onNewLine(line);
                }
            } else {
                if (isRoot() && line.endsWith(" $ su")) {
                    notifyInitialized();
                    return;
                }
                if (!isRoot() && line.endsWith(" $ ")) {
                    notifyInitialized();
                    return;
                }
            }
            if (mWaitingExit && line.endsWith(" exit")) {
                notifyExit();
            }
        }


        @Override
        protected void processInput(byte[] data, int offset, int count) {
            try {
                mOutputStream.write(data, offset, count);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void notifyExit() {
            synchronized (mExitLock) {
                mWaitingExit = false;
                mExitLock.notify();
            }
        }

        private void notifyInitialized() {
            mInitialized = true;
            synchronized (mInitLock) {
                mInitLock.notifyAll();
            }
            if (mCallback != null) {
                mCallback.onInitialized();
            }
        }

        @Override
        protected void onProcessExit() {
            super.onProcessExit();
            synchronized (mExitLock) {
                mWaitingExit = false;
                mExitLock.notify();
            }
        }

        @Override
        public void finish() {
            super.finish();
            mReadingThread.interrupt();
            try {
                mBufferedReader.close();
                mOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
