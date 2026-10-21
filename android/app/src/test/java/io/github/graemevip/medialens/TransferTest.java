package io.github.graemevip.medialens;

import static org.junit.Assert.*;

import org.junit.Test;

public class TransferTest {
  @Test
  public void displaysDecimalSizesAndMegabits() {
    Transfer t = new Transfer();
    assertTrue(t.read("ML_PROGRESS\tdownloading\t54000000\t128000000\tNA\t3000000\t25\tv"));
    assertEquals(42, t.percent());
    assertEquals("54.0 MB / 128.0 MB\n24.0 Mbps · 25s left", t.summary());
    assertEquals("1.50 GB", Transfer.size(1500000000));
  }

  @Test
  public void marksStreamSizeAsAnEstimate() {
    Transfer t = new Transfer();
    t.read("ML_PROGRESS\tdownloading\t10000000\tNA\t100000000\t1000000\t90\tv");
    assertTrue(t.summary().contains("≈100.0 MB"));
    assertEquals(10, t.percent());
    assertTrue(t.summary().contains("1m 30s left"));
  }

  @Test
  public void unknownSizeDoesNotInventACompletedPercentage() {
    Transfer t = new Transfer();
    t.read("ML_PROGRESS\tdownloading\t5000000\tNA\tNA\tNA\tNA\tv");
    assertEquals(0, t.percent());
    assertEquals("5.0 MB downloaded", t.summary());
  }

  @Test
  public void addsSeparateVideoAndAudioSizes() {
    Transfer t = new Transfer();
    t.read("ML_PROGRESS\tfinished\t100000000\t100000000\tNA\tNA\tNA\tv");
    t.read("ML_PROGRESS\tdownloading\t5000000\t10000000\tNA\t1000000\t5\ta");
    assertEquals(105000000, t.downloaded);
    assertEquals(110000000, t.total);
    assertEquals(95, t.percent());
  }

  @Test
  public void repeatedUpdatesDoNotDoubleCountAStream() {
    Transfer t = new Transfer();
    t.read("ML_PROGRESS\tdownloading\t10\t100\tNA\t1\t90\tv");
    t.read("ML_PROGRESS\tdownloading\t20\t100\tNA\t1\t80\tv");
    assertEquals(20, t.downloaded);
  }

  @Test
  public void rejectsOtherEngineMessagesAndInvalidNumbers() {
    Transfer t = new Transfer();
    assertFalse(t.read("[Merger] combining own fixture"));
    assertFalse(t.read("ML_PROGRESS\tbad"));
    assertTrue(t.read("ML_PROGRESS\tdownloading\t-1\tInfinity\tNA\tNaN\tNA\tv"));
    assertEquals(0, t.downloaded);
    assertEquals("0 KB downloaded", t.summary());
  }
}
