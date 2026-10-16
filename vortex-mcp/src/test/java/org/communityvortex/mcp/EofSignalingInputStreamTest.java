package org.communityvortex.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EofSignalingInputStreamTest {

  @Test
  void signalsOnceWhenTheClientClosesStdin() throws Exception {
    var signals = new AtomicInteger();
    try (var in = new EofSignalingInputStream(new ByteArrayInputStream("ab".getBytes()), signals::incrementAndGet)) {
      assertThat(in.read()).isEqualTo('a');
      assertThat(in.read(new byte[4], 0, 4)).isEqualTo(1);
      assertThat(signals.get()).isZero();
      assertThat(in.read()).isEqualTo(-1);
      assertThat(in.read(new byte[4], 0, 4)).isEqualTo(-1);
    }
    assertThat(signals.get()).isEqualTo(1);
  }
}
