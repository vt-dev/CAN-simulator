package com.visualthreat.testing;
import java.io.IOException;

/**
 * The interface captures local CAN data port & remote tcp CAN data port
 */
public interface ICANDataPort {
  boolean writeBytes(byte[] buffer) throws IOException;
  byte[] readBytes(int byteCount) throws IOException;
  int getInputBufferBytesCount() throws IOException;
  boolean openPort() throws IOException;
  boolean closePort() throws IOException;
  String getPortName();
}
