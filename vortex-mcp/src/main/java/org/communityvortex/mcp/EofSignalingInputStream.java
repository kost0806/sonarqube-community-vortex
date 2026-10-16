package org.communityvortex.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs a callback once when the wrapped stream reaches end of input (the MCP client closed stdin). */
final class EofSignalingInputStream extends FilterInputStream {

  private final Runnable onEof;
  private final AtomicBoolean signalled = new AtomicBoolean();

  EofSignalingInputStream(InputStream in, Runnable onEof) {
    super(in);
    this.onEof = onEof;
  }

  @Override
  public int read() throws IOException {
    return signalIfEof(super.read());
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    return signalIfEof(super.read(buffer, offset, length));
  }

  private int signalIfEof(int result) {
    if (result < 0 && signalled.compareAndSet(false, true)) {
      onEof.run();
    }
    return result;
  }
}
