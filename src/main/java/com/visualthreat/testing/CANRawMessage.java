package com.visualthreat.testing;

import java.util.Arrays;
import lombok.Data;

/**
 * Instances of the class is to store RAW message read from serial port
 */
@Data
public class CANRawMessage {
  private long timeStamp;
  private long seqNum;
  private byte[] data;

  public CANRawMessage(byte[] in, int length, long seqNum) {
    timeStamp = System.currentTimeMillis();
    data = Arrays.copyOf(in, length);
    this.seqNum = seqNum;
  }
}
